package com.github.kr328.clash

import android.os.DeadObjectException
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
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
    private val groupBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { design?.closeGroup() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, groupBack)
    }

    override suspend fun main() {
        var names = emptyList<String>()
        var states = emptyList<ProxyState>()
        var panel = ProxyDesign(this, names) { groupBack.isEnabled = it }
        panel.setRunning(clashRunning)
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

        fun reload(index: Int): Deferred<Boolean>? {
            val name = names.getOrNull(index) ?: return null
            val target = panel
            val state = states[index]
            val version = (versions[name] ?: 0L) + 1
            versions[name] = version
            reloads.remove(name)?.cancel()
            return async {
                try {
                    val group = withTimeout(10_000L) {
                        reloadLimit.withPermit { withClash { queryProxyGroup(name, ProxySort.Default) } }
                    }
                    if (target !== panel || versions[name] != version || !activityStarted || !clashRunning) return@async false
                    readFailureReported = false
                    state.now = group.now
                    target.updateGroup(index, group.proxies, group.type == "Selector", state)
                    true
                } catch (error: TimeoutCancellationException) {
                    report(target, error)
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report(target, error)
                    false
                } finally {
                    if (target === panel && versions[name] == version) {
                        reloads.remove(name)
                    }
                }
            }.also { reloads[name] = it }
        }

        fun refresh() {
            readFailureReported = false
            val generation = ++namesGeneration
            namesJob?.cancel()
            panel.setRunning(clashRunning)
            if (!clashRunning) {
                reloads.values.toList().forEach { it.cancel() }; reloads.clear()
                tests.values.toList().forEach { it.cancel() }; tests.clear()
                return
            }
            namesJob = launch {
                try {
                    val (next, selected) = withTimeout(8_000L) {
                        withClash {
                            val next = (listOf("GLOBAL") + queryProxyGroupNames(false)).distinct()
                            next to queryOverride(Clash.OverrideSlot.Session).routingTarget
                        }
                    }
                    if (generation != namesGeneration || !activityStarted || !clashRunning) return@launch
                    readFailureReported = false
                    if (next != names) {
                        reloads.values.toList().forEach { it.cancel() }; reloads.clear()
                        tests.values.toList().forEach { it.cancel() }; tests.clear()
                        versions.clear()
                        names = next
                        states = List(names.size) { ProxyState("?") }
                        val replacement = ProxyDesign(this@ProxyActivity, names) { groupBack.isEnabled = it }
                        replacement.setRunning(true)
                        panel.cancel()
                        panel = replacement
                        // Already on the main thread; replace together with its request source.
                        this@ProxyActivity.design = replacement
                        panelChanged.trySend(Unit)
                    }
                    panel.setCurrentSelection(selected)
                    names.indices.forEach { reload(it) }
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
                    writes.withLock {
                        withTimeout(10_000L) {
                            check(writeSelector(name, node)) { "所选节点或代理组已不可用" }
                        }
                    }
                    val position = names.indexOf(name)
                    if (position >= 0 && activityStarted) {
                        states[position].now = node
                        panel.setCurrentSelection(if (name == "GLOBAL") node else name)
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

        fun test(index: Int, node: String?) {
            val name = names.getOrNull(index) ?: return
            if (tests[name]?.isActive == true) return
            val target = panel
            tests[name] = launch {
                var completed = false
                try {
                    withTimeout(30_000L) { withClash { healthCheck(node ?: name) } }
                    if (target === panel && activityStarted && clashRunning) {
                        completed = reload(index)?.await() == true
                    }
                } catch (error: TimeoutCancellationException) {
                    report(target, error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report(target, error)
                } finally {
                    if (target === panel) { tests.remove(name); target.finishUrlTesting(index, completed) }
                }
            }
        }

        while (isActive) {
            val requestSource = panel
            select<Unit> {
                panelChanged.onReceive { }
                events.onReceive { event ->
                    when (event) {
                        Event.ActivityStart, Event.ProfileLoaded, Event.ServiceRecreated, Event.ClashStart, Event.ClashStop -> refresh()
                        Event.ActivityStop -> {
                            ++namesGeneration; namesJob?.cancel()
                            reloads.values.toList().forEach { it.cancel() }; reloads.clear()
                            tests.values.toList().forEach { it.cancel() }; tests.clear()
                        }
                        else -> Unit
                    }
                }
                requestSource.requests.onReceive { request ->
                    if (requestSource === panel) {
                        readFailureReported = false
                        when (request) {
                            ProxyDesign.Request.ReloadAll -> { if (names.isEmpty()) refresh() else names.indices.forEach { reload(it) } }
                            is ProxyDesign.Request.Select -> selectNode(request.index, request.name)
                            is ProxyDesign.Request.UrlTest -> test(request.index, request.node)
                        }
                    }
                }
            }
        }
    }

    /** Setting a selector is a single ordered write, never an implicit replay after binder death. */
    private suspend fun writeSelector(group: String, node: String): Boolean = withContext(Dispatchers.IO) {
        val remote = Remote.service.remote.get()
        try { remote.clash().patchSelector(group, node) }
        catch (error: DeadObjectException) { Remote.service.remote.reset(remote); throw error }
    }
}
