package com.github.kr328.clash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.RoyToolsShortcuts
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState
import com.mahmutalperenunal.adaptivehz.OneSetup
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
import com.mahmutalperenunal.adaptivehz.core.engine.model.RefreshRateApplyResult
import com.mahmutalperenunal.adaptivehz.core.service.AdaptiveHzActionHandler
import com.mahmutalperenunal.adaptivehz.core.shizuku.ShizukuAccess
import com.mahmutalperenunal.adaptivehz.core.shizuku.ShizukuAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.kr328.clash.design.R as DesignR

/** A short-lived entry point shared by launcher actions and the proxy tile. */
class RoyToolsShortcutActivity : ComponentActivity() {
    private val scope = MainScope()
    private var vpnPermissionPending = false
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vpnPermissionPending = false
        if (result.resultCode == RESULT_OK) {
            startProxyService(requestPermission = false)
        } else {
            Toast.makeText(this, "未授权VPN，代理未启动", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra("roytools_notification_action", false)) {
            com.github.kr328.clash.common.ui.TouchFeedback.click(android.view.View(this))
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        vpnPermissionPending = savedInstanceState?.getBoolean("vpn_permission_pending") ?: false
        if (vpnPermissionPending) return

        when (intent.action) {
            RoyToolsShortcuts.ACTION_TOGGLE_PROXY -> toggleProxy()
            RoyToolsShortcuts.ACTION_TOGGLE_REFRESH_ADAPTIVE -> {
                val mode = if (AdaptiveHzActionHandler.getCurrentMode(this) == AdaptiveHzMode.ADAPTIVE)
                    AdaptiveHzMode.OFF else AdaptiveHzMode.ADAPTIVE
                applyRefreshMode(mode)
            }
            RoyToolsShortcuts.ACTION_START_PROXY -> startProxy()
            RoyToolsShortcuts.ACTION_STOP_PROXY -> stopProxy()
            RoyToolsShortcuts.ACTION_REFRESH_DEFAULT -> applyRefreshMode(AdaptiveHzMode.OFF)
            RoyToolsShortcuts.ACTION_REFRESH_60 -> applyRefreshMode(AdaptiveHzMode.FORCE_MIN)
            RoyToolsShortcuts.ACTION_REFRESH_120 -> applyRefreshMode(AdaptiveHzMode.FORCE_MAX)
            RoyToolsShortcuts.ACTION_REFRESH_ADAPTIVE -> applyRefreshMode(AdaptiveHzMode.ADAPTIVE)
            else -> finish()
        }
    }

    private fun toggleProxy() {
        try {
            // A runtime can be alive while its profile is still loading, so test the
            // returned bundle rather than its nullable profile name or boot preference.
            val running = contentResolver.call(
                Uri.parse("content://${Authorities.STATUS_PROVIDER}"),
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            ) != null
            if (running) stopProxy() else startProxy()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read proxy state", e)
            Toast.makeText(this, "无法读取代理状态，请在代理页面重试", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun stopProxy() {
        runCatching { stopClashService() }.onFailure {
            Log.w(TAG, "Unable to stop proxy", it)
            Toast.makeText(this, "关闭代理失败，请在代理页面重试", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun startProxy() {
        scope.launch {
            try {
                val active = withProfile { queryActive() }
                if (active == null || !active.imported) {
                    Toast.makeText(this@RoyToolsShortcutActivity, DesignR.string.no_profile_selected,
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@RoyToolsShortcutActivity, ProfilesActivity::class.java))
                    finish()
                } else {
                    startProxyService(requestPermission = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                proxyStartFailed(e)
            }
        }
    }

    private fun startProxyService(requestPermission: Boolean) {
        try {
            val request = startClashService()
            if (request == null) {
                // The service's own state/broadcasts report when startup actually completes.
                finish()
            } else if (requestPermission) {
                vpnPermissionPending = true
                vpnPermission.launch(request)
            } else {
                proxyStartFailed(IllegalStateException("VPN permission was not granted"))
            }
        } catch (e: Exception) {
            vpnPermissionPending = false
            proxyStartFailed(e)
        }
    }

    private fun proxyStartFailed(error: Exception) {
        Log.w(TAG, "Unable to start proxy", error)
        Toast.makeText(this, DesignR.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun applyRefreshMode(mode: AdaptiveHzMode) {
        if (mode != AdaptiveHzMode.OFF && !com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs.isControlsEnabled(this)) {
            openRefreshPage("")
            return
        }
        val requestId = ++latestRefreshRequestId
        scope.launch {
            if (requestId != latestRefreshRequestId) {
                finish()
                return@launch
            }
            // Match the in-app controls: stopping remains available, and fixed rates
            // only need the persisted settings permission, not a live input monitor.
            if (mode != AdaptiveHzMode.OFF) {
                if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    openRefreshPage("缺少系统设置权限，请在刷新率页面配置辅助服务")
                    return@launch
                }
                runCatching { OneSetup.configure(this@RoyToolsShortcutActivity) }
                    .onFailure { Log.w(TAG, "Unable to restore refresh service configuration", it) }

                val ready = mode != AdaptiveHzMode.ADAPTIVE || awaitAdaptiveReady(requestId)
                if (requestId != latestRefreshRequestId) {
                    finish()
                    return@launch
                }
                if (!ready) {
                    val access = ShizukuAccess.readState()
                    val message = when (access) {
                        ShizukuAccessState.PERMISSION_REQUIRED ->
                            "辅助服务尚未授权，请在刷新率页面检查授权"
                        ShizukuAccessState.NOT_RUNNING ->
                            "辅助服务正在连接，请稍后重试"
                        ShizukuAccessState.READY ->
                            "辅助功能正在连接，请稍后重试"
                    }
                    Log.w(TAG, "Refresh entry waiting for service: access=$access " +
                        "accessibility=${AdaptiveHzRuntimeState.getAccessibilityState(this@RoyToolsShortcutActivity)}")
                    // A process-local binder or heartbeat can arrive after this entry starts.
                    // Do not turn that transient state into another wireless-pairing flow.
                    Toast.makeText(this@RoyToolsShortcutActivity, message, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
            executeRefreshMode(mode)
        }
    }

    private suspend fun awaitAdaptiveReady(requestId: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + REFRESH_CONNECTION_TIMEOUT_MS
        while (requestId == latestRefreshRequestId) {
            if (ShizukuAccess.readState() == ShizukuAccessState.READY &&
                AdaptiveHzRuntimeState.getAccessibilityState(this) ==
                AdaptiveHzRuntimeState.AccessibilityState.WORKING) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(100)
        }
        return false
    }

    private fun executeRefreshMode(mode: AdaptiveHzMode) {
        AdaptiveHzActionHandler.applyModeAsync(this, mode, onComplete = { result ->
            if (!com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs.isControlsEnabled(this)) {
                finish()
            } else if (!isFinishing && !isDestroyed) {
                when (result) {
                    is RefreshRateApplyResult.Failure,
                    is RefreshRateApplyResult.WriteRejected,
                    is RefreshRateApplyResult.VerificationMismatch ->
                        openRefreshPage("刷新率切换失败，请检查辅助服务")
                    is RefreshRateApplyResult.WrittenButUnverified -> {
                        Toast.makeText(this, "已发送刷新率设置，设备未能确认结果", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    is RefreshRateApplyResult.CustomFallbackApplied -> {
                        Toast.makeText(this, "自定义刷新率不可用，已使用设备默认档位", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    else -> finish()
                }
            }
        }, onSettled = {
            // Also closes this entry when a newer mode command supersedes it.
            if (!isFinishing && !isDestroyed) finish()
        })
    }

    private fun openRefreshPage(message: String) {
        if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("roytools_tab", "refresh")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("vpn_permission_pending", vpnPermissionPending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "RoyToolsShortcut"
        private const val REFRESH_CONNECTION_TIMEOUT_MS = 5_000L
        // Activity entry points and their coroutine continuations run on the main thread.
        private var latestRefreshRequestId = 0L
    }
}
