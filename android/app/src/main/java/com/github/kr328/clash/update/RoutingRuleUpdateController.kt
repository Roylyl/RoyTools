package com.github.kr328.clash.update

import android.app.Activity
import com.github.kr328.clash.core.model.RoutingRuleBundle
import com.github.kr328.clash.core.model.RoutingRuleDownload
import com.github.kr328.clash.core.model.RoutingRuleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutingRuleUpdateController(private val activity: Activity, private val scope: CoroutineScope) {
    private var task: Job? = null

    fun check(onStatus: (Boolean, String?) -> Unit, onUpdated: suspend () -> Unit) {
        if (task?.isActive == true) return
        task = scope.launch {
            onStatus(true, null)
            var message: String? = null
            try {
                val repository = RoutingRuleDownload()
                val (current, release, missingMusic) = withContext(Dispatchers.IO) {
                    val version = RoutingRuleStore.current(activity).version
                    val found = repository.check()
                    Triple(version, found, found.music != null && !RoutingRuleStore.hasMusicPolicy(activity, found.version))
                }
                val order = RoutingRuleBundle.compareVersions(release.version, current)
                if (order < 0 || (order == 0 && !missingMusic)) {
                    message = "目前已是最新版本$current"
                    return@launch
                }

                onStatus(true, "正在下载规则…")
                val text = if (order == 0) null else withContext(Dispatchers.IO) {
                    repository.download(release) { _, _ -> ensureActive() }
                }
                ensureActive()
                onStatus(true, "正在应用规则…")
                val saved = withContext(NonCancellable + Dispatchers.IO) {
                    RoutingRuleStore.activate(activity, release, text)
                }
                message = try {
                    onUpdated()
                    "规则已更新至${saved.version}"
                } catch (_: TimeoutCancellationException) {
                    "规则已保存至${saved.version}，重新开启VPN后生效。"
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    "规则已保存至${saved.version}，重新开启VPN后生效。"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = "规则更新失败：${error.message ?: "请检查网络后重试"}"
            } finally {
                onStatus(false, message)
            }
        }
    }
}
