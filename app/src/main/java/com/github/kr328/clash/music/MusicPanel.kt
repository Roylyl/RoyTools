package com.github.kr328.clash.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** A native navigation shell around the original music application's persistent audio engine. */
class MusicPanel(activity: Activity) {
    companion object {
        internal fun ensurePlaybackEngine(context: Context) {
            MusicEngine.get(context.applicationContext)
        }
        internal fun cancelDownloads(context: Context) {
            MusicEngine.get(context.applicationContext).settingsCommand("cancelDownload", null)
        }
        fun clearDiagnosticLogs(context: Context) {
            context.applicationContext.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE)
                .edit().putLong("logs_cleared_at", System.currentTimeMillis()).apply()
            MusicEngine.clearLogs()
        }
    }
    val view = FrameLayout(activity)
    private val engine = MusicEngine.get(activity)
    internal var hostActivity: Activity? = activity
        private set
    internal var started = false
        private set
    private var nativeSettings: NativeMusicSettingsPanel? = null
    private var nativeSettingsVisible = false
    private var miniIdentity: String? = null
    private val miniPlayer = NativeMiniPlayer(activity) { action -> engine.dispatch(action, null) }

    var onNavigationChanged: ((String?, Int) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(engine.navigationTitle, engine.navigationDepth)
        }

    val isSecondary: Boolean get() = engine.navigationDepth > 0
    val navigationPageKey: String? get() = engine.navigationPageKey

    init {
        engine.bind(this, activity)
        view.addView(miniPlayer, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(12)
        })
    }

    private fun dp(value: Int) = (view.resources.displayMetrics.density * value).toInt()

    fun onStart() {
        val activity = hostActivity ?: return
        val entering = !started
        started = true
        engine.bind(this, activity)
        engine.refresh()
        if (entering) engine.checkCatalog()
        updateKeepScreenOn()
        updateMiniPlayer()
    }

    /** Do not call WebView.onPause or pauseTimers: HTMLAudio remains the playback engine. */
    fun onStop() {
        started = false
        view.keepScreenOn = false
        updateMiniPlayer()
        hostActivity?.let(MusicDownloadCoordinator::dismiss)
        nativeSettings?.closeDialogs()
        engine.closeDialogs()
        engine.refreshPulse()
    }

    fun onDestroy() {
        started = false
        view.keepScreenOn = false
        nativeSettings?.let { it.view.clearAnimation(); it.dispose(); view.removeView(it.view) }
        nativeSettings = null
        engine.release(this)
        onNavigationChanged = null
        hostActivity = null
    }

    fun goHome() = engine.goHome()
    fun openPlayer() = engine.dispatch("openPlayer", null)
    fun handleBack(callback: (Boolean) -> Unit) = engine.handleBack(this, callback)

    internal fun navigationChanged(title: String?, depth: Int) {
        updateKeepScreenOn()
        updateNativeSettings()
        updateMiniPlayer()
        onNavigationChanged?.invoke(title, depth)
    }

    internal fun settingsChanged() {
        updateNativeSettings()
        updateMiniPlayer()
    }

    internal fun playbackChanged() = updateMiniPlayer()

    private fun updateMiniPlayer() {
        val state = MusicPlayback.state
        val visible = started && engine.miniPlayerVisible
        val identity = "$visible\n${state.playing}\n${state.title}\n${state.artist}\n${state.album}\n${state.artwork}\n${state.canPrevious}\n${state.canNext}"
        if (miniIdentity != identity) {
            miniIdentity = identity
            miniPlayer.render(state, visible)
        }
        if (visible && view.indexOfChild(miniPlayer) != view.childCount - 1) miniPlayer.bringToFront()
    }

    private fun updateKeepScreenOn() {
        val enabled = hostActivity?.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE)
            ?.getBoolean("keep_screen_on_full", true) ?: true
        view.keepScreenOn = started && enabled && engine.navigationPageKey == "player"
    }

    private fun updateNativeSettings() {
        val state = engine.settingsState
        val show = engine.navigationPageKey == "settings" && state?.overlayOpen != true
        if (!show) {
            nativeSettings?.let { setSettingsVisible(it, false) }
            return
        }
        val activity = hostActivity ?: return
        val panel = nativeSettings ?: NativeMusicSettingsPanel(activity, engine::settingsCommand, ::updateKeepScreenOn).also {
            nativeSettings = it
            view.addView(it.view, FrameLayout.LayoutParams(-1, -1))
        }
        panel.render(state)
        // Keep the settings surface opaque and full-height; reserve scrolling
        // space inside it instead of exposing the underlying WebView at the bottom.
        val reserve = if (state?.hasCurrent == true) dp(90) else 0
        if (panel.view.paddingBottom != reserve) {
            panel.view.setPadding(panel.view.paddingLeft, panel.view.paddingTop, panel.view.paddingRight, reserve)
        }
        panel.view.clipToPadding = false
        setSettingsVisible(panel, true)
    }

    private fun setSettingsVisible(panel: NativeMusicSettingsPanel, visible: Boolean) {
        val changed = nativeSettingsVisible != visible
        nativeSettingsVisible = visible
        if (!changed) return
        panel.view.clearAnimation()
        if (visible) {
            panel.setVisible(true)
            if (changed && started) panel.view.startAnimation(AnimationUtils.loadAnimation(view.context,
                com.github.kr328.clash.design.R.anim.roytools_push_in))
        } else if (changed && started && engine.navigationPageKey != "player" && engine.settingsState?.overlayOpen != true) {
            panel.closeDialogs()
            val animation = AnimationUtils.loadAnimation(view.context, com.github.kr328.clash.design.R.anim.roytools_pop_out)
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit
                override fun onAnimationRepeat(animation: Animation?) = Unit
                override fun onAnimationEnd(animation: Animation?) {
                    if (!nativeSettingsVisible) panel.setVisible(false)
                }
            })
            panel.view.startAnimation(animation)
        } else panel.setVisible(false)
    }
}

/** Owned by the process rather than an Activity; its Context is rebound on every recreation. */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
private class MusicEngine(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var messageToast: Toast? = null
    private val webContext = MutableContextWrapper(context)
    private val assets = MusicAssets(app)
    private var owner = WeakReference<MusicPanel>(null)
    private val commands = ArrayDeque<Pair<String, Double?>>()
    private val settingsCommands = ArrayDeque<Pair<String, Any?>>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private val popups = mutableSetOf<WebView>()
    private var ready = false
    private var visualReady = false
    private var visualRequest = 0L
    private var appliedTheme: String? = null
    private var appliedBackground: Int? = null
    @Volatile private var generation = 0
    private var probing = false
    private var pulseScheduled = false
    @Volatile private var trustedPage = false
    @Volatile private var theme = effectiveTheme(context)
    private var latestState: String? = null
    var navigationTitle: String? = null
        private set
    var navigationDepth = 0
        private set
    var navigationPageKey: String? = null
        private set
    var miniPlayerVisible = false
        private set
    var settingsState: MusicSettingsState? = null
        private set
    private lateinit var web: WebView

    init {
        // Service-worker fetches bypass WebViewClient, including a worker's install-time shell fetches.
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply {
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = false
            }
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    assets.intercept(request)
            })
        }
        web = createWebView()
        MusicPlayback.attach(app, ::dispatch)
        web.loadUrl(MusicAssets.HOME)
    }

    fun bind(panel: MusicPanel, activity: Activity) {
        if (owner.get() !== panel) {
            closeDialogs()
            (web.parent as? ViewGroup)?.removeView(web)
            owner = WeakReference(panel)
        }
        webContext.baseContext = activity
        theme = effectiveTheme(activity)
        applyBackground()
        panel.view.setBackgroundColor(backgroundColor())
        if (web.parent !== panel.view) {
            (web.parent as? ViewGroup)?.removeView(web)
            panel.view.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        }
        if (ready) applyTheme()
        revealFirstFrame()
        panel.navigationChanged(navigationTitle, navigationDepth)
        refreshPulse()
    }

    fun release(panel: MusicPanel) {
        if (owner.get() !== panel) return
        closeDialogs()
        popups.toList().forEach(::disposePopup)
        (web.parent as? ViewGroup)?.removeView(web)
        owner.clear()
        // The application context keeps playback alive without retaining the destroyed Activity/window.
        webContext.baseContext = app
        refreshPulse()
    }

    fun refresh() {
        if (ready) {
            applyTheme()
            dispatch("sync", null)
            evaluate("window.RoyToolsMusicApp.pulse()")
        }
        refreshPulse()
    }

    fun dispatch(action: String, position: Double?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dispatch(action, position) }
            return
        }
        if (action !in setOf("play", "pause", "toggle", "next", "previous", "seek", "openPlayer", "openQueue", "sync")) return
        enqueue(action, position?.takeIf { it.isFinite() }?.coerceAtLeast(0.0))
    }

    fun goHome() = enqueue("home", null)

    fun checkCatalog() = evaluate("window.RoyToolsMusicApp.checkCatalog?.()")

    fun settingsCommand(action: String, value: Any?) {
        when (action) {
            "preload" -> if (value !is Boolean) return
            "budget" -> if (value !is Long || value !in MusicSettingsState.budgets) return
            "sync", "clearAudio", "clearLyrics", "clearCovers", "downloadAll", "cancelDownload", "openDownloads", "clearAll", "downloadLyrics", "downloadCovers", "checkCatalog" -> Unit
            else -> return
        }
        if (!ready) {
            if (settingsCommands.size >= 16) settingsCommands.removeFirst()
            settingsCommands.addLast(action to value)
            return
        }
        if (action == "clearCovers" || action == "clearAll") MusicArtwork.clearCache(app)
        val encodedValue = if (value is Boolean || value is Long) value.toString() else "null"
        evaluate("window.RoyToolsMusicApp.settings(${JSONObject.quote(action)},$encodedValue)")
    }

    private fun enqueue(action: String, position: Double?) {
        if (!ready) {
            if (commands.size >= 64) commands.removeFirst()
            commands.addLast(action to position)
            return
        }
        if (action == "home") evaluate("window.RoyToolsMusicApp.goHome()")
        else if (action == "clearLogs") evaluate("window.RoyToolsMusicApp.clearLogs()")
        else evaluate("window.RoyToolsMusicApp.command(${JSONObject.quote(action)},${position ?: "null"})")
        refreshPulse()
    }

    fun handleBack(panel: MusicPanel, callback: (Boolean) -> Unit) {
        if (!ready || owner.get() !== panel) {
            callback(false)
            return
        }
        val page = generation
        val pageKey = navigationPageKey
        web.evaluateJavascript("(function(){try{return !!window.RoyToolsMusicApp.handleBack()}catch(e){return false}})()") { result ->
            val handled = result == "true"
            if (page == generation && trustedPage && owner.get() === panel &&
                panel.started && panel.hostActivity != null &&
                (handled || navigationPageKey == pageKey)) callback(handled)
        }
    }

    private fun applyTheme() {
        if (!ready || !trustedPage || appliedTheme == theme) return
        appliedTheme = theme
        evaluate("window.RoyToolsMusicApp.setTheme(${JSONObject.quote(theme)})")
    }

    private fun revealFirstFrame() {
        if (visualReady || !ready || !trustedPage || !web.isAttachedToWindow) return
        val target = web
        val page = generation
        val request = ++visualRequest
        target.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (target === web && page == generation && trustedPage && requestId == visualRequest) {
                    visualReady = true
                    target.alpha = 1f
                }
            }
        })
    }

    private fun evaluate(expression: String) {
        if (ready && trustedPage) web.evaluateJavascript("(function(){try{$expression}catch(e){}})()", null)
    }

    private fun probeReady(attempt: Int = 0) {
        if (ready || !trustedPage || probing) return
        probing = true
        val page = generation
        web.evaluateJavascript("typeof window.RoyToolsMusicApp==='object'") { value ->
            probing = false
            if (page != generation || !trustedPage) return@evaluateJavascript
            if (value == "true") {
                ready = true
                applyTheme()
                while (commands.isNotEmpty()) {
                    val (action, position) = commands.removeFirst()
                    enqueue(action, position)
                }
                while (settingsCommands.isNotEmpty()) {
                    val (action, value) = settingsCommands.removeFirst()
                    settingsCommand(action, value)
                }
                refresh()
                revealFirstFrame()
            } else if (attempt < 40) {
                main.postDelayed({ if (page == generation) probeReady(attempt + 1) }, 250)
            }
        }
    }

    private val pulse = object : Runnable {
        override fun run() {
            pulseScheduled = false
            if (!shouldPulse()) return
            evaluate("window.RoyToolsMusicApp.pulse()")
            refreshPulse()
        }
    }

    // The service owns background sync; only the visible panel needs a one-second UI pulse.
    private fun shouldPulse(): Boolean = owner.get()?.started == true

    fun refreshPulse() {
        if (shouldPulse()) {
            if (!pulseScheduled) {
                pulseScheduled = true
                main.postDelayed(pulse, 1000)
            }
        } else {
            main.removeCallbacks(pulse)
            pulseScheduled = false
        }
    }

    private fun currentActivity(): Activity? = owner.get()?.hostActivity?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun backgroundColor() = if (theme == "dark") Color.rgb(18, 18, 20) else Color.rgb(242, 242, 247)
    private fun applyBackground() {
        val color = backgroundColor()
        owner.get()?.view?.setBackgroundColor(color)
        if (appliedBackground != color) {
            appliedBackground = color
            web.setBackgroundColor(color)
        }
    }

    private fun createWebView(): WebView = WebView(webContext).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            builtInZoomControls = false
            displayZoomControls = false
            @Suppress("DEPRECATION")
            forceDark = WebSettings.FORCE_DARK_OFF
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = false
        }
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        setBackgroundColor(backgroundColor())
        alpha = 0f
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "RoyToolsMusicBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame && !MusicAssets.isPage(request.url)) return MusicAssets.error(403, "Forbidden")
                return assets.intercept(request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (MusicAssets.isPage(request.url)) return false
                if (request.isForMainFrame) openExternal(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                closeDialogs()
                generation++
                ready = false
                visualReady = false
                appliedTheme = null
                view.alpha = 0f
                miniPlayerVisible = false
                owner.get()?.playbackChanged()
                probing = false
                trustedPage = url != null && MusicAssets.isPage(Uri.parse(url))
                if (!trustedPage) {
                    view.stopLoading()
                    view.removeJavascriptInterface("RoyToolsMusicBridge")
                    view.addJavascriptInterface(Bridge(), "RoyToolsMusicBridge")
                    view.loadUrl(MusicAssets.HOME)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && MusicAssets.isPage(Uri.parse(url))) probeReady()
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (url != null && MusicAssets.isPage(Uri.parse(url))) revealFirstFrame()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                visualReady = false
                appliedTheme = null
                appliedBackground = null
                miniPlayerVisible = false
                owner.get()?.playbackChanged()
                trustedPage = false
                generation++
                MusicDownloadCoordinator.fail(app, "音乐引擎已重启，下载已停止，请重新开始")
                closeDialogs()
                popups.toList().forEach(::disposePopup)
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                latestState?.let { payload ->
                    runCatching { JSONObject(payload).put("playing", false).put("paused", true).toString() }
                        .getOrNull()?.let { MusicPlayback.update(app, it) }
                }
                web = createWebView()
                owner.get()?.let { panel -> panel.view.addView(web, 0, FrameLayout.LayoutParams(-1, -1)) }
                web.loadUrl(MusicAssets.HOME)
                currentActivity()?.let { Toast.makeText(it, "音乐页面已恢复，请继续播放", Toast.LENGTH_SHORT).show() }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
                scriptDialog(url, message, result, false)

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
                scriptDialog(url, message, result, true)

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || currentActivity() == null) return false
                val popup = WebView(app).apply {
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                popups.add(popup)
                var handled = false
                fun route(uri: Uri) {
                    if (handled || uri.toString() == "about:blank") return
                    handled = true
                    openExternal(uri)
                    main.post { disposePopup(popup) }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        route(request.url)
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (url != null) route(Uri.parse(url))
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                main.postDelayed({ disposePopup(popup) }, 10000)
                return true
            }
        }
        setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun disposePopup(popup: WebView) {
        if (popups.remove(popup)) {
            popup.stopLoading()
            popup.destroy()
        }
    }

    private fun scriptDialog(url: String?, message: String?, result: JsResult, confirm: Boolean): Boolean {
        val activity = currentActivity()
        if (!trustedPage || url == null || !MusicAssets.isPage(Uri.parse(url)) || activity == null) {
            result.cancel()
            return true
        }
        var settled = false
        val builder = RoyToolsDialogBuilder(activity).setTitle("音乐").setMessage(message.orEmpty())
            .setPositiveButton("确定") { _, _ -> settled = true; result.confirm() }
        if (confirm) builder.setNegativeButton("取消") { _, _ -> settled = true; result.cancel() }
        val dialog = builder.create()
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            if (!settled) result.cancel()
            dialogs.remove(dialog)
        }
        dialog.show()
        return true
    }

    fun closeDialogs() = dialogs.toList().forEach { it.dismiss() }

    private fun openExternal(uri: Uri) {
        if (MusicAssets.isOrigin(uri) || uri.scheme?.lowercase() !in setOf("https", "http", "mailto", "tel", "sms", "geo", "market")) return
        val activity = currentActivity() ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity, "无法打开此链接", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun getTheme(): String = theme
        @JavascriptInterface fun showMusicDownload() {
            if (!trustedPage) return
            val page = generation
            main.post {
                if (!trustedPage || page != generation) return@post
                val panel = owner.get() ?: return@post
                val activity = currentActivity() ?: return@post
                if (panel.started && panel.view.isShown) MusicDownloadCoordinator.show(activity)
            }
        }
        @JavascriptInterface fun showToast(message: String) {
            if (!trustedPage || message.length > 4096 || message.isBlank()) return
            val page = generation
            main.post {
                if (!trustedPage || page != generation) return@post
                val panel = owner.get() ?: return@post
                if (!panel.started || !panel.view.isShown || !panel.view.isAttachedToWindow) return@post
                messageToast?.cancel()
                messageToast = Toast.makeText(app, message.trim(), Toast.LENGTH_SHORT).also { it.show() }
            }
        }
        @JavascriptInterface fun getLogsClearedAt(): Long =
            app.getSharedPreferences("roytools_music_native", Context.MODE_PRIVATE).getLong("logs_cleared_at", 0L)

        @JavascriptInterface fun chooseOption(payload: String) {
            if (!trustedPage || payload.length > 131072) return
            val page = generation
            val request = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val id = request.optString("id")
            val title = when (id) {
                "artist" -> "歌手"
                "sort-primary" -> "第一顺序"
                "sort-secondary" -> "第二顺序"
                "sort-direction" -> "方向"
                else -> return
            }
            val rows = request.optJSONArray("options") ?: return
            if (rows.length() !in 1..512) return
            val options = runCatching {
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val value = row.getString("value")
                    val label = row.getString("label")
                    require(value.length <= 512 && label.isNotBlank() && label.length <= 512)
                    value to label
                }.also { require(it.map { option -> option.first }.distinct().size == it.size) }
            }.getOrNull() ?: return
            val selected = request.optString("selected")
            if (selected.length > 512) return
            main.post {
                val panel = owner.get() ?: return@post
                val activity = currentActivity() ?: return@post
                val pickerPage = navigationPageKey
                fun isCurrentHost() = trustedPage && ready && page == generation &&
                    navigationPageKey == pickerPage &&
                    owner.get() === panel && panel.hostActivity === activity && panel.started &&
                    panel.view.isShown && panel.view.isAttachedToWindow && web.parent === panel.view
                if (!isCurrentHost()) return@post
                closeDialogs()
                val dialog = RoyToolsDialogBuilder(activity).setTitle(title)
                    .setSingleChoiceItems(options.map { it.second }.toTypedArray(),
                        options.indexOfFirst { it.first == selected }) { choice, index ->
                        if (isCurrentHost() && index in options.indices) {
                            evaluate("window.RoyToolsMusicApp.chooseOption(${JSONObject.quote(id)},${JSONObject.quote(options[index].first)})")
                        }
                        choice.dismiss()
                    }.setNegativeButton("取消", null).create()
                dialogs.add(dialog)
                dialog.setOnDismissListener { dialogs.remove(dialog) }
                dialog.show()
            }
        }

        @JavascriptInterface fun onMusicLog(payload: String) {
            if (!trustedPage || payload.length > 16384) return
            com.github.kr328.clash.log.UnifiedLogs.appendMusic(app, payload)
        }

        @JavascriptInterface fun onPlaybackState(payload: String) {
            if (!trustedPage || payload.length > 131072) return
            val page = generation
            main.post {
                if (!trustedPage || page != generation) return@post
                latestState = payload
                MusicPlayback.update(app, payload)
                owner.get()?.playbackChanged()
                refreshPulse()
            }
        }

        @JavascriptInterface fun onNavigationState(payload: String) {
            if (!trustedPage || payload.length > 4096) return
            val page = generation
            val state = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val depth = if (state.optBoolean("secondary")) state.optInt("depth", 1).coerceIn(1, 32) else 0
            val title = if (depth > 0) state.optString("title").take(120).takeIf { it.isNotBlank() } else null
            val pageKey = state.optString("pageKey").take(512).takeIf { it.isNotBlank() }
            main.post {
                if (!trustedPage || page != generation) return@post
                if (pageKey != navigationPageKey) closeDialogs()
                navigationDepth = depth
                navigationTitle = title
                navigationPageKey = pageKey
                miniPlayerVisible = state.optBoolean("miniPlayerVisible", false)
                owner.get()?.navigationChanged(title, depth)
            }
        }

        @JavascriptInterface fun onSettingsState(payload: String) {
            if (!trustedPage || payload.length > 16384) return
            val page = generation
            val state = MusicSettingsState.parse(payload) ?: return
            main.post {
                if (!trustedPage || page != generation) return@post
                settingsState = state
                MusicDownloadCoordinator.update(app, state.downloadBatch)
                owner.get()?.settingsChanged()
            }
        }

        @JavascriptInterface fun share(url: String, title: String) {
            if (!trustedPage || url.length > 8192) return
            val page = generation
            val uri = Uri.parse(url)
            if (uri.scheme !in setOf("https", "http")) return
            main.post {
                if (!trustedPage || page != generation) return@post
                val activity = currentActivity() ?: return@post
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title.take(160)).putExtra(Intent.EXTRA_TEXT, url)
                try { activity.startActivity(Intent.createChooser(send, "分享歌曲")) }
                catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(activity, "没有可分享的应用", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface fun copy(text: String) {
            if (!trustedPage || text.length > 262144) return
            val page = generation
            main.post {
                if (!trustedPage || page != generation) return@post
                val activity = currentActivity() ?: return@post
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("音乐", text))
            }
        }

        @JavascriptInterface fun haptic(kind: String) {
            if (!trustedPage || kind !in setOf("click", "step")) return
            val page = generation
            main.post {
                if (!trustedPage || page != generation) return@post
                val activity = currentActivity() ?: return@post
                val target = View(activity)
                if (kind == "step") TouchFeedback.step(target) else TouchFeedback.click(target)
            }
        }
    }

    companion object {
        private var instance: MusicEngine? = null
        fun clearLogs() { instance?.enqueue("clearLogs", null) }
        fun get(context: Context): MusicEngine = instance ?: MusicEngine(context).also { instance = it }

        private fun effectiveTheme(context: Context): String {
            val fallback = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                Color.WHITE else Color.BLACK
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val text = try { attributes.getColor(0, fallback) } finally { attributes.recycle() }
            return if (Color.luminance(text) > 0.5) "dark" else "light"
        }
    }
}
