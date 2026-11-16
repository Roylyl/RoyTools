package com.github.kr328.clash.update

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.RoutingRuleBundle
import com.github.kr328.clash.core.model.RoutingRuleDownload
import com.github.kr328.clash.core.model.RoutingRuleStore
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class RoutingRuleUpdateController(private val activity: Activity, private val scope: CoroutineScope) {
    private var task: Job? = null

    fun check(onUpdated: suspend () -> Unit) {
        if (task?.isActive == true) return
        task = scope.launch {
            var dialog: AlertDialog? = null
            try {
                dialog = RoyToolsDialogBuilder(activity).setTitle("检查规则更新")
                    .setMessage("正在连接GitHub…").setNegativeButton("取消") { _, _ -> task?.cancel() }.show()
                dialog.setOnCancelListener { task?.cancel() }
                val repository = RoutingRuleDownload()
                val (current, release) = withContext(Dispatchers.IO) {
                    RoutingRuleStore.current(activity).version to repository.check()
                }
                dialog.dismiss(); dialog = null
                if (RoutingRuleBundle.compareVersions(release.version, current) <= 0) {
                    toast("目前已是最新版本$current")
                    return@launch
                }
                val confirmed = suspendCancellableCoroutine<Boolean> { continuation ->
                    val prompt = RoyToolsDialogBuilder(activity).setTitle("发现规则更新")
                        .setMessage("当前版本$current\n最新版本${release.version}\n\n${release.notes}\n\n仅更新AI与规则模式的分流规则，保留订阅和选定节点。")
                        .setNegativeButton("取消") { _, _ -> if (continuation.isActive) continuation.resume(false) }
                        .setPositiveButton("更新") { _, _ -> if (continuation.isActive) continuation.resume(true) }.show()
                    prompt.setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                    continuation.invokeOnCancellation { activity.runOnUiThread { prompt.dismiss() } }
                }
                if (!confirmed) return@launch
                val density = activity.resources.displayMetrics.density
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((24*density).toInt(), (12*density).toInt(), (24*density).toInt(), 0)
                }
                val status = TextView(activity).apply { text = "正在下载规则…" }
                val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
                content.addView(status); content.addView(progress)
                dialog = RoyToolsDialogBuilder(activity).setTitle("更新规则${release.version}").setView(content)
                    .setNegativeButton("取消") { _, _ -> task?.cancel() }.show()
                dialog.setOnCancelListener { task?.cancel() }
                val job = currentCoroutineContext()
                var lastPercent = -1
                val text = withContext(Dispatchers.IO) {
                    repository.download(release) { bytes, total ->
                        job.ensureActive()
                        val percent = (bytes.toLong() * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            activity.runOnUiThread {
                                if (job.isActive) { progress.progress = percent; status.text = "已下载$percent%" }
                            }
                        }
                    }
                }
                ensureActive()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                status.text = "正在保存规则…"
                val saved = withContext(NonCancellable + Dispatchers.IO) { RoutingRuleStore.activate(activity, text) }
                dialog.dismiss(); dialog = null
                try {
                    onUpdated()
                    toast("规则已更新至${saved.version}")
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { toast("规则已保存至${saved.version}，重新开启代理后生效") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                dialog?.dismiss(); dialog = null
                RoyToolsDialogBuilder(activity).setTitle("规则更新失败")
                    .setMessage((error.message ?: "无法连接GitHub，请检查网络后重试") + "\n\n已保留原有规则。")
                    .setPositiveButton("知道了", null).show()
            } finally { dialog?.dismiss() }
        }
    }

    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
