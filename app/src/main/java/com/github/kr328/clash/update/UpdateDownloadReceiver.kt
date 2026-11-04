package com.github.kr328.clash.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Foreground completion opens the installer; background completion offers an install notification. */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (completedId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = UpdateDownload.query(context)
                if (state == null || state.id != completedId || state.status !in listOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED)) return@launch
                if (!UpdateDownload.claimCompletion(context, completedId)) return@launch
                if (!UpdateDownload.isCurrentCompletion(context, completedId)) return@launch
                if (state.status == DownloadManager.STATUS_SUCCESSFUL) {
                    UpdateNotifications.downloadReady(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!UpdateDownload.isCurrentCompletion(context, completedId)) return@post
                        val activity = com.github.kr328.clash.util.ApplicationObserver.visibleActivity()
                        if (activity != null && activity !is UpdateActivity) {
                            runCatching { activity.startActivity(Intent(activity, UpdateActivity::class.java)) }
                        }
                    }
                } else UpdateNotifications.downloadFailed(context, state.error ?: "更新包下载失败，请重新下载。")
            } catch (e: Exception) {
                Log.w("Unable to handle application update download", e)
            } finally {
                pending.finish()
            }
        }
    }
}
