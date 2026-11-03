package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val names = try {
            withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.github.kr328.clash.common.log.Log.w("无法读取代理节点", e)
            android.widget.Toast.makeText(this, "无法读取节点列表，请返回后重试", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            try {
                                val newNames = withClash {
                                    queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                                }

                                if (newNames != names) {
                                    startActivity(ProxyActivity::class.intent)

                                    finish()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                design.showExceptionToast(e)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                try {
                                    val group = reloadLock.withPermit {
                                        withClash {
                                            queryProxyGroup(names[it.index], uiStore.proxySort)
                                        }
                                    }
                                    val state = states[it.index]

                                    state.now = group.now

                                    design.updateGroup(
                                        it.index,
                                        group.proxies,
                                        group.type == "Selector",
                                        state,
                                        unorderedStates
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    design.finishUrlTesting(it.index)
                                    design.showExceptionToast(e)
                                }
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            try {
                                withClash {
                                    patchSelector(names[it.index], it.name)
                                }
                                states[it.index].now = it.name

                                design.requestRedrawVisible()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                design.showExceptionToast(e)
                            }
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                try {
                                    withClash {
                                        healthCheck(names[it.index])
                                    }

                                    design.requests.send(ProxyDesign.Request.Reload(it.index))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    design.finishUrlTesting(it.index)
                                    design.showExceptionToast(e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
