package com.github.kr328.clash

import android.os.DeadObjectException
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        var names = emptyList<String>()
        var states = emptyList<ProxyState>()
        var links = emptyMap<String, ProxyState>()
        var panel = ProxyDesign(this, names, uiStore)
        setContentDesign(panel)
        val panelChanged = Channel<Unit>(Channel.CONFLATED)
        val reloadLimit = Semaphore(10)
        val writes = Mutex()
        val pendingSelections = mutableListOf<Pair<String, String>>()
        val reloads = mutableMapOf<String, Job>()
        val tests = mutableMapOf<String, Job>()
        val versions = mutableMapOf<String, Long>()
        var namesJob: Job? = null
        var namesGeneration = 0L
        var readFailureReported = false

        suspend fun report(target: ProxyDesign, error: Exception) {
            if (target !== panel || !activityStarted || readFailureReported) return
            readFailureReported = true
            if (error is TimeoutCancellationException) target.showToast("代理服务响应超时，请稍后重试", ToastDuration.Long)
            else target.showExceptionToast(error)
        }

        fun reload(index: Int) {
            val name = names.getOrNull(index) ?: return
            val target = panel
            val state = states[index]
            val version = (versions[name] ?: 0L) + 1
            versions[name] = version
            reloads.remove(name)?.cancel()
            reloads[name] = launch {
                try {
                    val group = withTimeout(10_000L) {
                        reloadLimit.withPermit { withClash { queryProxyGroup(name, uiStore.proxySort) } }
                    }
                    if (target !== panel || versions[name] != version || !activityStarted) return@launch
                    readFailureReported = false
                    state.now = group.now
                    target.updateGroup(index, group.proxies, group.type == "Selector", state, links)
                } catch (error: TimeoutCancellationException) {
                    report(target, error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report(target, error)
                } finally {
                    if (target === panel && versions[name] == version) {
                        reloads.remove(name)
                        target.finishUrlTesting(index)
                    }
                }
            }
        }

        fun refresh() {
            readFailureReported = false
            val generation = ++namesGeneration
            namesJob?.cancel()
            namesJob = launch {
                try {
                    val next = withTimeout(8_000L) { withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) } }
                    if (generation != namesGeneration || !activityStarted) return@launch
                    readFailureReported = false
                    if (next != names) {
                        reloads.values.forEach { it.cancel() }; reloads.clear()
                        tests.values.forEach { it.cancel() }; tests.clear()
                        versions.clear()
                        names = next
                        states = List(names.size) { ProxyState("?") }
                        links = names.indices.associate { names[it] to states[it] }
                        val replacement = ProxyDesign(this@ProxyActivity, names, uiStore)
                        panel.cancel()
                        panel = replacement
                        // Already on the main thread; replace together with its request source.
                        this@ProxyActivity.design = replacement
                        panelChanged.trySend(Unit)
                    }
                    names.indices.forEach(::reload)
                } catch (error: TimeoutCancellationException) {
                    if (generation == namesGeneration) report(panel, error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == namesGeneration) report(panel, error)
                }
            }
        }

        fun selectNode(index: Int, node: String) {
            val name = names.getOrNull(index) ?: return
            val request = name to node
            if (pendingSelections.lastOrNull() == request) return
            if (pendingSelections.size >= 16) {
                launch { panel.showToast("节点切换正在进行，请稍后再试", ToastDuration.Long) }
                return
            }
            pendingSelections.add(request)
            versions[name] = (versions[name] ?: 0L) + 1
            reloads.remove(name)?.cancel()
            launch {
                try {
                    writes.withLock { withTimeout(10_000L) { writeSelector(name, node) } }
                    val position = names.indexOf(name)
                    if (position >= 0 && activityStarted) {
                        states[position].now = node
                        panel.requestRedrawVisible()
                        reload(position)
                    }
                } catch (error: TimeoutCancellationException) {
                    if (activityStarted) panel.showToast("节点切换超时，请检查服务后重试", ToastDuration.Long)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (activityStarted) panel.showExceptionToast(error)
                } finally { pendingSelections.remove(request) }
            }
        }

        fun test(index: Int) {
            val name = names.getOrNull(index) ?: return
            if (tests[name]?.isActive == true) return
            val target = panel
            tests[name] = launch {
                try {
                    withTimeout(30_000L) { withClash { healthCheck(name) } }
                    if (target === panel && activityStarted) reload(index)
                } catch (error: TimeoutCancellationException) {
                    report(target, error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report(target, error)
                } finally {
                    if (target === panel) { tests.remove(name); target.finishUrlTesting(index) }
                }
            }
        }

        while (isActive) {
            val requestSource = panel
            select<Unit> {
                panelChanged.onReceive { }
                events.onReceive { event ->
                    when (event) {
                        Event.ActivityStart, Event.ProfileLoaded, Event.ServiceRecreated, Event.ClashStart -> refresh()
                        Event.ActivityStop -> {
                            ++namesGeneration; namesJob?.cancel()
                            reloads.values.forEach { it.cancel() }; reloads.clear()
                            tests.values.forEach { it.cancel() }; tests.clear()
                        }
                        else -> Unit
                    }
                }
                requestSource.requests.onReceive { request ->
                    if (requestSource === panel) {
                        readFailureReported = false
                        when (request) {
                        ProxyDesign.Request.ReLaunch -> refresh()
                        ProxyDesign.Request.ReloadAll -> { if (names.isEmpty()) refresh() else names.indices.forEach(::reload) }
                        is ProxyDesign.Request.Reload -> reload(request.index)
                        is ProxyDesign.Request.Select -> selectNode(request.index, request.name)
                        is ProxyDesign.Request.UrlTest -> test(request.index)
                        }
                    }
                }
            }
        }
    }

    /** Setting a selector is a single ordered write, never an implicit replay after binder death. */
    private suspend fun writeSelector(group: String, node: String) = withContext(Dispatchers.IO) {
        val remote = Remote.service.remote.get()
        try { remote.clash().patchSelector(group, node) }
        catch (error: DeadObjectException) { Remote.service.remote.reset(remote); throw error }
    }
}
