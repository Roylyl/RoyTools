package com.github.kr328.clash.update

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.github.kr328.clash.R
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class UpdateActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var check: Button
    private lateinit var cancelDownload: Button
    private lateinit var backgroundDownload: Button
    private lateinit var transferInfo: TextView
    private var speedMeter = DownloadProgressMeter()
    private var monitor: Job? = null
    private var offeredDownload = -1L
    private var visible = false

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            openInstaller()
        } else {
            status.setText(R.string.update_install_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = com.github.kr328.clash.design.store.UiStore(this).darkMode
        val night = when (mode) {
            com.github.kr328.clash.design.model.DarkMode.ForceDark -> true
            com.github.kr328.clash.design.model.DarkMode.ForceLight -> false
            else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        theme.applyStyle(if (night) com.github.kr328.clash.design.R.style.AppThemeDark
            else com.github.kr328.clash.design.R.style.AppThemeLight, true)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = resolveThemedColor(com.github.kr328.clash.design.R.attr.glassCanvasStart)
        window.navigationBarColor = window.statusBarColor
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        if (savedInstanceState == null) overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_push_in,
            com.github.kr328.clash.design.R.anim.roytools_push_out)
        title = getString(R.string.update_title)
        offeredDownload = savedInstanceState?.getLong("offered_download", -1) ?: -1
        val spacing = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val cardPadding = (18 * resources.displayMetrics.density).roundToInt()
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
            setBackgroundResource(com.github.kr328.clash.design.R.drawable.roytools_card)
        }
        fun label(text: CharSequence = "") = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, spacing / 2, 0, spacing / 2)
            content.addView(this)
        }
        label(getString(R.string.update_current, com.github.kr328.clash.BuildConfig.VERSION_NAME))
        val updateOptions = layoutInflater.inflate(com.github.kr328.clash.design.R.layout.roytools_update_options, content, false)
        content.addView(updateOptions)
        updateOptions.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(com.github.kr328.clash.design.R.id.roytools_auto_updates).apply {
            isChecked = UpdateScheduler.enabled(this@UpdateActivity)
            setOnCheckedChangeListener { _, enabled -> UpdateScheduler.setEnabled(this@UpdateActivity, enabled) }
            updateOptions.setOnClickListener { performClick() }
        }
        status = label(getString(R.string.update_checking))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            content.addView(this)
        }
        transferInfo = label().apply { visibility = View.GONE }
        action = MaterialButton(this).apply { visibility = View.GONE; content.addView(this) }
        cancelDownload = MaterialButton(this).apply {
            setText(R.string.update_cancel_download)
            visibility = View.GONE
            content.addView(this)
            setOnClickListener {
                monitor?.cancel()
                launch {
                    try {
                        withContext(Dispatchers.IO) { UpdateDownload.cancel(this@UpdateActivity) }
                        if (visible) beginCheckOrDownload()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showDownloadFailure(e)
                    }
                }
            }
        }
        backgroundDownload = MaterialButton(this).apply {
            text = "后台下载"
            visibility = View.GONE
            content.addView(this)
            setOnClickListener { finish() }
        }
        check = MaterialButton(this).apply {
            setText(com.github.kr328.clash.design.R.string.check_for_updates)
            content.addView(this)
            setOnClickListener {
                monitor?.cancel()
                monitor = launch {
                    try {
                    val state = withContext(Dispatchers.IO) { UpdateDownload.query(this@UpdateActivity) }
                    if (state?.status == DownloadManager.STATUS_FAILED) {
                        withContext(Dispatchers.IO) { UpdateDownload.cancel(this@UpdateActivity) }
                    }
                    checkRelease()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { showDownloadFailure(e) }
                }
            }
        }
        label(getString(R.string.update_schedule_help))
        details = label().apply { setTextIsSelectable(true) }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveThemedColor(com.github.kr328.clash.design.R.attr.glassCanvasStart))
        }
        shell.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(spacing, (14 * resources.displayMetrics.density).roundToInt(),
                spacing, (8 * resources.displayMetrics.density).roundToInt())
            addView(android.widget.ImageButton(this@UpdateActivity).apply {
                setImageResource(com.github.kr328.clash.design.R.drawable.roytools_back)
                background = null
                contentDescription = "返回"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams((44 * resources.displayMetrics.density).roundToInt(),
                (48 * resources.displayMetrics.density).roundToInt()))
            addView(TextView(this@UpdateActivity).apply {
                text = title
                textSize = 25f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            })
        })
        shell.addView(ScrollView(this).apply {
            setPadding(spacing * 4 / 5, (8 * resources.displayMetrics.density).roundToInt(),
                spacing * 4 / 5, spacing * 4 / 5)
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        listOf(action, cancelDownload, backgroundDownload, check).forEach { button ->
            button.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = spacing / 2 }
        }
        setContentView(shell)
        UpdateNotifications.dismiss(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(com.github.kr328.clash.design.R.anim.roytools_pop_in,
            com.github.kr328.clash.design.R.anim.roytools_pop_out)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        beginCheckOrDownload()
    }

    override fun onStop() {
        visible = false
        monitor?.cancel()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("offered_download", offeredDownload)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun beginCheckOrDownload() {
        monitor?.cancel()
        speedMeter = DownloadProgressMeter()
        monitor = launch {
            try {
                var state = withContext(Dispatchers.IO) { UpdateDownload.query(this@UpdateActivity) }
                if (state == null) {
                    checkRelease()
                    return@launch
                }
                while (isActive && state != null) {
                    renderDownload(state)
                    if (state.status == DownloadManager.STATUS_SUCCESSFUL ||
                        state.status == DownloadManager.STATUS_FAILED) break
                    delay(1000)
                    state = withContext(Dispatchers.IO) { UpdateDownload.query(this@UpdateActivity) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showDownloadFailure(e)
            }
        }
    }

    private suspend fun checkRelease() {
        com.github.kr328.clash.common.log.Log.i("开始检查应用更新")
        status.setText(R.string.update_checking)
        check.isEnabled = false
        action.visibility = View.GONE
        cancelDownload.visibility = View.GONE
        backgroundDownload.visibility = View.GONE
        transferInfo.visibility = View.GONE
        progress.visibility = View.GONE
        details.text = ""
        try {
            val found = withContext(Dispatchers.IO) {
                ReleaseRepository.fetchLatest(com.github.kr328.clash.BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
            }
            if (found == null) {
                com.github.kr328.clash.common.log.Log.i("应用更新检查完成，当前已是最新版本")
                status.setText(R.string.update_latest)
            } else {
                com.github.kr328.clash.common.log.Log.i("发现应用更新：${found.versionName}")
                status.text = getString(R.string.update_available, found.versionName)
                details.text = getString(R.string.update_asset, found.assetName) + "\n\n" + found.body
                action.setText(R.string.update_download)
                action.visibility = View.VISIBLE
                action.isEnabled = true
                action.setOnClickListener { startDownload(found) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.github.kr328.clash.common.log.Log.w("应用更新检查失败", e)
            status.text = if (e is java.io.IOException && e.message?.contains("发行页") == true)
                e.message else getString(R.string.update_check_failed)
        } finally {
            check.isEnabled = true
        }
    }

    private fun startDownload(found: ReleaseInfo) {
        com.github.kr328.clash.common.log.Log.i("开始下载应用更新：${found.versionName}")
        action.isEnabled = false
        check.isEnabled = false
        launch {
            try {
                withContext(Dispatchers.IO) { UpdateDownload.start(this@UpdateActivity, found) }
                offeredDownload = -1
                if (visible) beginCheckOrDownload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                action.isEnabled = true
                showDownloadFailure(e)
            }
        }
    }

    private fun renderDownload(state: DownloadState) {
        val snapshot = speedMeter.sample(state.downloadedBytes, state.totalBytes, android.os.SystemClock.elapsedRealtime())
        when (state.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                status.setText(R.string.update_download_complete)
                progress.visibility = View.GONE
                cancelDownload.visibility = View.GONE
                backgroundDownload.visibility = View.GONE
                transferInfo.visibility = View.VISIBLE
                transferInfo.text = "100%·下载完成"
                check.isEnabled = true
                action.setText(R.string.update_install)
                action.visibility = View.VISIBLE
                action.isEnabled = true
                action.setOnClickListener { requestInstall() }
                if (visible && offeredDownload != state.id) {
                    offeredDownload = state.id
                    requestInstall()
                }
            }
            DownloadManager.STATUS_FAILED -> showFailure(state.error ?: getString(R.string.update_download_failed))
            else -> {
                val percent = snapshot.percent ?: 0
                status.text = if (state.totalBytes > 0) getString(R.string.update_downloading, state.tag, percent)
                    else getString(R.string.update_download_waiting)
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.totalBytes <= 0
                progress.progress = percent
                action.visibility = View.GONE
                cancelDownload.visibility = View.VISIBLE
                backgroundDownload.visibility = View.VISIBLE
                transferInfo.visibility = View.VISIBLE
                val speed = if (state.status == DownloadManager.STATUS_RUNNING) snapshot.megabytesPerSecond else 0.0
                transferInfo.text = java.lang.String.format(java.util.Locale.ROOT, "%.2fMB/s\n%.2fMB/%s·%s",
                    speed, state.downloadedBytes.coerceAtLeast(0) / 1_000_000.0,
                    if (state.totalBytes > 0) java.lang.String.format(java.util.Locale.ROOT, "%.2fMB", state.totalBytes / 1_000_000.0) else "未知",
                    snapshot.percent?.let { "$it%" } ?: "等待获取文件大小")
                check.isEnabled = false
            }
        }
    }

    private fun requestInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(this)
                .setMessage(R.string.update_install_permission)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        status.text = getString(R.string.update_install_failed, e.localizedMessage.orEmpty())
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        } else {
            openInstaller()
        }
    }

    private fun openInstaller() {
        launch {
            try {
                val intent = withContext(Dispatchers.IO) { UpdateDownload.installerIntent(this@UpdateActivity) }
                if (visible) {
                    UpdateNotifications.dismiss(this@UpdateActivity)
                    startActivity(intent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.update_install_failed, e.localizedMessage.orEmpty())
            }
        }
    }

    private fun showDownloadFailure(error: Exception) {
        showFailure(if (error is java.io.IOException && !error.message.isNullOrBlank())
            error.message!! else getString(R.string.update_download_failed))
    }

    private fun showFailure(message: CharSequence) {
        com.github.kr328.clash.common.log.Log.w("应用更新：$message")
        status.text = message
        progress.visibility = View.GONE
        cancelDownload.visibility = View.GONE
        backgroundDownload.visibility = View.GONE
        transferInfo.visibility = View.GONE
        action.visibility = View.GONE
        check.isEnabled = true
    }
}

