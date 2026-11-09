package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.update.UpdateActivity
import com.github.kr328.clash.update.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.applyRecentTaskVisibility
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.github.kr328.clash.common.log.Log
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.MainDesign.Companion.TAB_MUSIC
import com.github.kr328.clash.design.MainDesign.Companion.TAB_PROXY
import com.github.kr328.clash.design.MainDesign.Companion.TAB_REFRESH
import com.github.kr328.clash.design.MainDesign.Companion.TAB_FORWARD
import com.github.kr328.clash.design.MainDesign.Companion.TAB_SETTINGS
import com.github.kr328.clash.music.MusicPanel
import com.github.kr328.clash.music.MusicPlayback
import com.github.kr328.clash.log.UnifiedLogs
import com.github.kr328.clash.log.exportModuleLog
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder

class MainActivity : BaseActivity<MainDesign>() {
    private enum class SettingsPage { Home, Theme, About, Background }
    private var forwardPanel: com.github.kr328.clash.forward.ForwardPanel? = null
    private var selectedTab = 0
    private var settingsPage = SettingsPage.Home
    private var snapshotJob: Job? = null
    private var trafficJob: Job? = null
    private var proxyActionJob: Job? = null
    private var proxyModeJob: Job? = null
    private val proxyModeLock = Mutex()
    private var trafficReadFailed = false
    private var musicPanel: MusicPanel? = null
    private var refreshPanel: com.mahmutalperenunal.adaptivehz.RefreshPanel? = null

    private fun selectTabHome(tab: Int) {
        val reselectingMusic = selectedTab == TAB_MUSIC && tab == TAB_MUSIC && musicPanel != null
        val returning = (selectedTab == TAB_SETTINGS && settingsPage != SettingsPage.Home) ||
            (selectedTab == TAB_MUSIC && musicPanel?.isSecondary == true)
        when (tab) {
            TAB_MUSIC -> musicPanel?.goHome()
            TAB_FORWARD -> forwardPanel?.goHome()
            TAB_PROXY -> design?.resetProxyScroll()
            TAB_REFRESH -> refreshPanel?.scrollToTop()
            TAB_SETTINGS -> settingsPage = SettingsPage.Home
        }
        // goHome owns the music page transition. Reattaching its live WebView also
        // restarts layout/theme sync and visibly flashes an already open home page.
        if (reselectingMusic) return
        selectTab(tab)
        if (returning && tab != TAB_MUSIC) design?.animateReturnToHome()
    }

    private fun selectTab(tab: Int) {
        if (tab != TAB_REFRESH) refreshPanel?.onStop()
        if (tab != TAB_MUSIC) musicPanel?.onStop()
        val current = design ?: return
        selectedTab = tab
        when (tab) {
            TAB_MUSIC -> {
                val panel = musicPanel ?: MusicPanel(this).also { created ->
                    musicPanel = created
                    created.onNavigationChanged = { title, depth ->
                        if (selectedTab == TAB_MUSIC) {
                            current.headerTitle(if (depth > 0) title ?: "音乐" else null)
                        }
                    }
                }
                current.showMusic(panel.view)
                panel.onStart()
            }
            TAB_FORWARD -> {
                val panel = forwardPanel ?: com.github.kr328.clash.forward.ForwardPanel(this).also { created ->
                    forwardPanel = created
                    created.onNavigationChanged = { secondary -> if(selectedTab == TAB_FORWARD) current.headerTitle(if(secondary) "转发设置" else null) }
                }
                current.showForward(panel.view)
                current.headerTitle(if(panel.isSecondary) "转发设置" else null)
                panel.refresh()
            }
            TAB_PROXY -> current.showProxy()
            TAB_REFRESH -> {
                val panel = refreshPanel ?: com.mahmutalperenunal.adaptivehz.RefreshPanel(this)
                    .also { refreshPanel = it }
                current.showRefresh(panel.view)
                current.headerTitle(null)
                panel.onStart()
            }
            TAB_SETTINGS -> showSettingsPage(settingsPage)
        }
        if (tab == TAB_PROXY && clashRunning) readProxyTraffic(current)
    }

    private fun showSettingsPage(page: SettingsPage) {
        val current = design ?: return
        val returning = settingsPage != SettingsPage.Home && page == SettingsPage.Home
        settingsPage = page
        when (page) {
            SettingsPage.Home -> current.showSettings(uiStore.darkMode, returning,
                onTheme = { showSettingsPage(SettingsPage.Theme) },
                onAbout = { showSettingsPage(SettingsPage.About) },
                 onBackground = { showSettingsPage(SettingsPage.Background) },
                onCheckUpdates = { startActivity(Intent(this, UpdateActivity::class.java)) })
            SettingsPage.Background -> current.showBackground(
                autoRestart = packageManager.getComponentEnabledSetting(
                    android.content.ComponentName(this, RestartReceiver::class.java)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                batteryExempt = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                onAutoRestart = { enabled -> packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(this, RestartReceiver::class.java),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP) },
                onBattery = { openSystemSettings(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) },
                onAppBackground = { openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                onVpn = { openSystemSettings(android.provider.Settings.ACTION_VPN_SETTINGS) },
                keepAlive = com.mahmutalperenunal.adaptivehz.OneSetup.isKeepAliveEnabled(this),
                hideIcon = uiStore.hideAppIcon,
                hideRecents = uiStore.hideFromRecents,
                onKeepAlive = {
                    com.github.kr328.clash.service.store.ServiceStore(this).aggressiveKeepAlive = it
                    com.mahmutalperenunal.adaptivehz.OneSetup.setKeepAliveEnabled(this, it)
                },
                onHideIcon = { hide ->
                    uiStore.hideAppIcon = hide
                    packageManager.setComponentEnabledSetting(
                        mainActivityAlias,
                        if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP)
                    if (hide) ShortcutManagerCompat.removeAllDynamicShortcuts(this) else setupShortcuts()
                },
                onHideRecents = { hidden ->
                    uiStore.hideFromRecents = hidden
                    com.github.kr328.clash.util.ApplicationObserver.createdActivities.toList()
                        .forEach { it.applyRecentTaskVisibility(hidden) }
                })
            SettingsPage.Theme -> current.showTheme(uiStore.darkMode) { mode ->
                if (uiStore.darkMode != mode) {
                    uiStore.darkMode = mode
                    ApplicationObserver.createdActivities.toList().forEach { it.recreate() }
                }
            }
            SettingsPage.About -> {
                val version = current.showAbout(BuildConfig.VERSION_NAME,
                    onViewLogs = { module -> startActivity(Intent(this, ModuleLogsActivity::class.java)
                        .putExtra(ModuleLogsActivity.EXTRA_MODULE, module)) },
                    onExportLogs = { module -> launch { exportModuleLogs(module) } },
                    onClearLogs = ::confirmClearLogs)
                launch {
                    val coreVersion = withContext(Dispatchers.IO) {
                        runCatching { Bridge.nativeCoreVersion().replace("_", "-") }.getOrNull()
                    }
                    if (!coreVersion.isNullOrBlank() && !coreVersion.contains("unknown", ignoreCase = true) && version.isAttachedToWindow) {
                        version.text = "版本" + BuildConfig.VERSION_NAME + "\n代理核心" + coreVersion
                    }
                }
            }
        }
    }

    private fun navigateBack() {
        if(selectedTab == TAB_FORWARD && forwardPanel?.isSecondary == true) { forwardPanel?.goHome(); return }
        if (selectedTab == TAB_MUSIC) {
            musicPanel?.handleBack { handled -> if (!handled && selectedTab == TAB_MUSIC) finish() }
            return
        }
        if (selectedTab == TAB_SETTINGS && settingsPage != SettingsPage.Home) {
            showSettingsPage(SettingsPage.Home)
            return
        }
        finish()
    }

    private suspend fun exportModuleLogs(name: String) {
        val module = runCatching { UnifiedLogs.Module.valueOf(name) }.getOrDefault(UnifiedLogs.Module.App)
        exportModuleLog(module)
    }

    private fun confirmClearLogs() {
        RoyToolsDialogBuilder(this).setTitle("清空所有日志")
            .setMessage("清空音乐、代理、刷新率及应用日志。清空后会继续自动采集。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> launch {
                try {
                    MusicPanel.clearDiagnosticLogs(this@MainActivity)
                    withContext(Dispatchers.IO) { UnifiedLogs.clearAll(this@MainActivity) }
                    android.widget.Toast.makeText(this@MainActivity, "所有日志已清空", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { design?.showExceptionToast(e) }
            } }.show()
    }

    private fun openSystemSettings(action: String, packageUri: Boolean = false) {
        val requested = Intent(action).apply {
            if (packageUri) data = android.net.Uri.parse("package:$packageName")
        }
        val choices = listOf(requested, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName")), Intent(android.provider.Settings.ACTION_SETTINGS))
        for (intent in choices) {
            try { startActivity(intent); return }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        android.widget.Toast.makeText(this, DesignR.string.system_settings_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun readProxyStatus(current: MainDesign) {
        snapshotJob?.cancel()
        snapshotJob = launch {
            try {
                withTimeout(10_000L) { current.fetch() }
            } catch (error: TimeoutCancellationException) {
                Log.w("读取代理状态超时")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("读取代理状态失败", error)
            }
        }
    }

    private fun readProxyTraffic(current: MainDesign) {
        if (trafficJob?.isActive == true) return
        trafficJob = launch {
            try {
                withTimeout(5_000L) { current.fetchTraffic() }
                trafficReadFailed = false
            } catch (error: TimeoutCancellationException) {
                if (!trafficReadFailed) Log.w("读取代理流量超时")
                trafficReadFailed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!trafficReadFailed) Log.w("读取代理流量失败", error)
                trafficReadFailed = true
            }
        }
    }

    private fun toggleProxy(current: MainDesign) {
        if (proxyActionJob?.isActive == true) return
        proxyActionJob = launch {
            try {
                if (clashRunning) stopClashService() else current.startClash()
            } catch (error: TimeoutCancellationException) {
                current.showToast("代理服务连接超时，请重试", ToastDuration.Long)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("切换代理失败", error)
                current.showExceptionToast(error)
            } finally {
                if (currentCoroutineContext().isActive) readProxyStatus(current)
            }
        }
    }

    private fun changeProxyMode(current: MainDesign, request: MainDesign.Request) {
        proxyModeJob?.cancel()
        proxyModeJob = launch {
            try {
                proxyModeLock.withLock {
                    withTimeout(10_000L) {
                        withClash {
                            val override = queryOverride(Clash.OverrideSlot.Session)
                            override.mode = when (request) {
                                MainDesign.Request.ModeRule -> TunnelState.Mode.Rule
                                MainDesign.Request.ModeGlobal -> TunnelState.Mode.Global
                                MainDesign.Request.ModeDirect -> TunnelState.Mode.Direct
                                else -> null
                            }
                            patchOverride(Clash.OverrideSlot.Session, override)
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                current.showToast("代理模式切换超时，请重试", ToastDuration.Long)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("切换代理模式失败", error)
                current.showExceptionToast(error)
            } finally {
                if (currentCoroutineContext().isActive) readProxyStatus(current)
            }
        }
    }
    override fun onStop() {
        musicPanel?.onStop()
        refreshPanel?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        forwardPanel?.destroy()
        musicPanel?.onDestroy()
        refreshPanel?.onStop()
        super.onDestroy()
    }
    override suspend fun main() {
        val design = MainDesign(this)
        design.onTabSelected = ::selectTabHome
        design.onHeaderBack = ::navigateBack

        setContentDesign(design)

        selectTab(selectedTab)
        consumeMusicIntent(intent)
        readProxyStatus(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))
        Log.d("主页操作队列已就绪")

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> readProxyStatus(design)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    Log.d("主页处理操作：$it")
                    when (it) {
                        MainDesign.Request.ToggleStatus -> toggleProxy(design)
                        MainDesign.Request.OpenLanSharing ->
                            startActivity(OverrideSettingsActivity::class.intent.putExtra(
                                OverrideSettingsActivity.EXTRA_LAN_ONLY, true
                            ))
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.ModeDefault,
                        MainDesign.Request.ModeRule,
                        MainDesign.Request.ModeGlobal,
                        MainDesign.Request.ModeDirect -> changeProxyMode(design, it)
                        MainDesign.Request.OpenAppSettings -> startActivity(AppSettingsActivity::class.intent)
                        MainDesign.Request.OpenNetworkSettings -> startActivity(NetworkSettingsActivity::class.intent)
                        MainDesign.Request.OpenOverrideSettings -> startActivity(OverrideSettingsActivity::class.intent)
                        MainDesign.Request.OpenProviders -> startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.AddProxyTile -> addProxyTile()
                        MainDesign.Request.AddProxyWidget -> addProxyWidget()
                    }
                }
                if (clashRunning && activityStarted && selectedTab == TAB_PROXY) {
                    ticker.onReceive {
                        readProxyTraffic(design)
                    }
                }
            }
        }
    }

    private fun addProxyWidget() {
        RoyToolsDialogBuilder(this).setTitle("添加RoyTools桌面小组件")
            .setMessage("1.返回桌面，长按空白处，或在桌面双指捏合。\n" +
                "2.进入“小组件”，找到RoyTools。\n" +
                "3.选择横版4×2小组件，拖到桌面的空白区域。\n" +
                "4.点击“完成”或退出编辑模式。\n\n" +
                "请预留足够空间，小组件可控制代理、刷新率和音乐播放。")
            .setPositiveButton("知道了", null).show()
    }

    private fun addProxyTile() {
        RoyToolsDialogBuilder(this).setTitle("添加控制中心代理开关")
            .setMessage("1.下拉控制中心，展开全部快捷按钮。\n" +
                "2.点击“编辑”或“＋”。\n" +
                "3.找到地球图标的“代理开关”，将它拖入已添加区域。\n" +
                "4.点击“完成”或保存。\n\n" +
                "添加后即可在控制中心开启或关闭RoyTools代理。")
            .setPositiveButton("知道了", null).show()
    }

    override fun onStart() {
        super.onStart()
        UpdateScheduler.checkOnLaunch(this)
        if (selectedTab == TAB_FORWARD) forwardPanel?.refresh()
        com.github.kr328.clash.widget.ProxyWidgetProvider.refresh(this)
        if (selectedTab == TAB_SETTINGS && settingsPage == SettingsPage.Background) {
            design?.refreshBackgroundStatus(
                getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        }
        if (selectedTab == TAB_REFRESH) refreshPanel?.onStart()
        if (selectedTab == TAB_MUSIC) musicPanel?.onStart()
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        setMode(state.mode)
        setProxyMode(withClash { queryOverride(Clash.OverrideSlot.Session).mode })

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTrafficRates(queryTrafficNow())
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withTimeout(10_000L) { withProfile { queryActive() } }

        if (active == null || !active.imported) {
            showActionDialog(getString(DesignR.string.no_profile_selected), DesignR.string.profiles) {
                startActivity(ProfilesActivity::class.intent)
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_tab", selectedTab)
        outState.putInt("navigation_schema", 2)
        outState.putString("settings_page", settingsPage.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra("roytools_tab")) {
            "music" -> selectTab(TAB_MUSIC)
            "proxy" -> selectTab(TAB_PROXY)
            "refresh" -> selectTab(TAB_REFRESH)
            "settings" -> selectTab(TAB_SETTINGS)
        }
        consumeMusicIntent(intent)
    }
    private fun consumeMusicIntent(source: Intent) {
        if (source.getBooleanExtra("roytools_music_download", false)) {
            com.github.kr328.clash.music.MusicDownloadCoordinator.show(this)
            source.removeExtra("roytools_music_download")
        }
        if (source.getStringExtra("roytools_tab") != "music") return
        if (source.getBooleanExtra("roytools_music_player", false)) musicPanel?.openPlayer()
        source.getStringExtra("roytools_music_action")?.let { action ->
            val position = if (source.hasExtra("roytools_music_position")) source.getDoubleExtra("roytools_music_position", 0.0) else null
            MusicPlayback.dispatch(action, position)
        }
        source.removeExtra("roytools_music_player")
        source.removeExtra("roytools_music_action")
        source.removeExtra("roytools_music_position")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val requested = when (intent.getStringExtra("roytools_tab")) {
            "proxy" -> TAB_PROXY
            "refresh" -> TAB_REFRESH
            "settings" -> TAB_SETTINGS
            else -> TAB_MUSIC
        }
        selectedTab = if (savedInstanceState?.containsKey("selected_tab") == true) {
            val stored = savedInstanceState.getInt("selected_tab")
            (if (savedInstanceState.getInt("navigation_schema", 1) < 2) stored + 1 else stored).coerceIn(TAB_MUSIC, TAB_FORWARD)
        } else requested
        settingsPage = savedInstanceState?.getString("settings_page")?.let { name ->
            SettingsPage.entries.firstOrNull { it.name == name }
        } ?: SettingsPage.Home
        val prefs = getSharedPreferences("roytools_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("theme_initialized", false)) {
            uiStore.darkMode = DarkMode.Auto
            prefs.edit().putBoolean("theme_initialized", true).apply()
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val colors = obtainStyledAttributes(intArrayOf(DesignR.attr.glassCanvasStart, DesignR.attr.glassToolbarSurface))
        window.statusBarColor = colors.getColor(0, window.statusBarColor)
        window.navigationBarColor = colors.getColor(1, window.navigationBarColor)
        colors.recycle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        setupShortcuts()
    }

    private fun setupShortcuts() {
        com.github.kr328.clash.util.RoyToolsShortcuts.update(this)
    }
}
