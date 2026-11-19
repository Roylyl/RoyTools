package com.github.kr328.clash

import android.content.res.Configuration
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.DayNight
import com.github.kr328.clash.design.util.resolveThemedBoolean
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.ActivityResultLifecycle
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.github.kr328.clash.design.R

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer {
    
    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private val nextRequestKey = AtomicInteger(0)
    private var dayNight: DayNight = DayNight.Day
    private var lastPageLaunch: String? = null
    private var lastPageLaunchAt = 0L

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // Guard repeated ordinary page clicks only. Result contracts, permission
        // requests and external pickers must always retain their own callbacks.
        if (requestCode == -1 && intent.component?.packageName == packageName) {
            val signature = intent.toUri(0)
            val now = SystemClock.uptimeMillis()
            if (isFinishing || isDestroyed ||
                signature == lastPageLaunch && now - lastPageLaunchAt < 400L) return
            lastPageLaunch = signature
            lastPageLaunchAt = now
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onResume() {
        super.onResume()
        lastPageLaunch = null
    }

    protected abstract suspend fun main()

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        val requestKey = nextRequestKey.getAndIncrement().toString()

        ActivityResultLifecycle().use { lifecycle, start ->
            suspendCoroutine { c ->
                activityResultRegistry.register(requestKey, lifecycle, contracts) {
                    c.resume(it)
                }.apply { start() }.launch(input)
            }
        }
    }

    suspend fun setContentDesign(design: D) {
        suspendCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && javaClass.name != "com.github.kr328.clash.MainActivity") {
            overridePendingTransition(R.anim.roytools_push_in, R.anim.roytools_push_out)
        }
        applyDayNight()

        launch {
            main()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }

    override fun onDestroy() {
        design?.cancel()
        cancel()
        super.onDestroy()
    }

    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            try {
                // A disconnected service or a missing stop broadcast must not
                // trap navigation behind an unfinished save/cleanup operation.
                withTimeout(10_000L) { defer() }
                finishAfterDeferredOperation()
            } catch (error: TimeoutCancellationException) {
                deferRunning = false
                showDeferredExitFailure()
            } catch (error: CancellationException) {
                deferRunning = false
                throw error
            } catch (error: Exception) {
                deferRunning = false
                com.github.kr328.clash.common.log.Log.w("退出前的保存或清理失败", error)
                showDeferredExitFailure()
            }
        }
    }

    private fun finishAfterDeferredOperation() {
        super.finish()
        overridePendingTransition(R.anim.roytools_pop_in, R.anim.roytools_pop_out)
    }

    private fun showDeferredExitFailure() {
        if (isFinishing || isDestroyed) return
        com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(this)
            .setTitle("退出前操作未完成")
            .setMessage("保存或清理暂时未能完成，当前页面和输入已保留。可以重试；放弃并返回可能丢失尚未保存的更改。")
            .setNegativeButton("留在此页", null)
            .setNeutralButton("放弃并返回") { _, _ -> finishAfterDeferredOperation() }
            .setPositiveButton("重试") { _, _ -> finish() }
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (queryDayNight(newConfig) != dayNight) {
            ApplicationObserver.createdActivities.forEach {
                it.recreate()
            }
        }
    }

    open fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        this.onBackPressed()
        return true
    }

    override fun onProfileChanged() {
        events.trySend(Event.ProfileChanged)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        events.trySend(Event.ProfileUpdateCompleted)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        events.trySend(Event.ProfileUpdateFailed)
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showExceptionToast(ClashException(cause))
            }
        }
    }

    private fun queryDayNight(config: Configuration = resources.configuration): DayNight {
        return when (uiStore.darkMode) {
            DarkMode.Auto -> if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DayNight.Night else DayNight.Day
            DarkMode.ForceLight -> DayNight.Day
            DarkMode.ForceDark -> DayNight.Night
        }
    }

    private fun applyDayNight(config: Configuration = resources.configuration) {
        val dayNight = queryDayNight(config)
        when (dayNight) {
            DayNight.Night -> theme.applyStyle(R.style.AppThemeDark, true)
            DayNight.Day -> theme.applyStyle(R.style.AppThemeLight, true)
        }

        window.isAllowForceDarkCompat = false
        window.isSystemBarsTranslucentCompat = true
        
        window.statusBarColor = resolveThemedColor(android.R.attr.statusBarColor)
        window.navigationBarColor = resolveThemedColor(android.R.attr.navigationBarColor)

        if (Build.VERSION.SDK_INT >= 23) {
            window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)
        }

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }

        this.dayNight = dayNight
    }

    enum class Event {
        ServiceRecreated,
        ActivityStart,
        ActivityStop,
        ClashStop,
        ClashStart,
        ProfileLoaded,
        ProfileChanged,
        ProfileUpdateCompleted,
        ProfileUpdateFailed,
    }
}

