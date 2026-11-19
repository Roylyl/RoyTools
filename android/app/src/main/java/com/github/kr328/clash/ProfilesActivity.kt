package com.github.kr328.clash

import android.os.DeadObjectException
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val panel = ProfilesDesign(this)
        setContentDesign(panel)
        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))
        val writes = Mutex()
        val pending = mutableListOf<String>()
        var fetchJob: Job? = null
        var fetchGeneration = 0L
        var navigationPending = false
        var savePrompt: Job? = null

        fun refresh() {
            val generation = ++fetchGeneration
            fetchJob?.cancel()
            fetchJob = launch {
                try {
                    val profiles = withTimeout(8_000L) { withProfile { queryAll() } }
                    if (generation == fetchGeneration && activityStarted) panel.patchProfiles(profiles)
                } catch (_: TimeoutCancellationException) {
                    if (activityStarted) panel.showToast("配置服务连接超时，请稍后重试", ToastDuration.Long)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (activityStarted) panel.showExceptionToast(error)
                }
            }
        }

        fun mutate(key: String, block: suspend () -> Unit) {
            val bulk = key == "update-all"
            val repeated = if (key.startsWith("active:")) pending.lastOrNull() == key else key in pending
            if (repeated) return
            if (pending.size >= 16 || key.startsWith("update:") && "update-all" in pending ||
                bulk && pending.any { it.startsWith("update:") }) {
                if (bulk) panel.finishUpdateAll()
                launch { panel.showToast("配置操作正在进行，请完成后再试", ToastDuration.Long) }
                return
            }
            pending.add(key)
            launch {
                try {
                    // Mutex preserves submission order without making navigation wait.
                    writes.withLock { withTimeout(if (bulk) 30_000L else 10_000L) { block() } }
                    if (activityStarted) refresh()
                } catch (_: TimeoutCancellationException) {
                    if (activityStarted) panel.showToast("配置操作超时，请检查服务后重试", ToastDuration.Long)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (activityStarted) panel.showExceptionToast(error)
                } finally {
                    pending.remove(key)
                    // Also runs when waiting for the service failed before entering the RPC.
                    if (bulk) panel.finishUpdateAll()
                }
            }
        }

        fun navigate(intent: android.content.Intent) {
            if (navigationPending) return
            navigationPending = true
            startActivity(intent)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { event ->
                    when (event) {
                        Event.ActivityStart -> { navigationPending = false; refresh() }
                        Event.ProfileChanged, Event.ServiceRecreated -> refresh()
                        Event.ActivityStop -> { ++fetchGeneration; fetchJob?.cancel() }
                        else -> Unit
                    }
                }
                panel.requests.onReceive { request ->
                    when (request) {
                        ProfilesDesign.Request.Create -> navigate(NewProfileActivity::class.intent)
                        is ProfilesDesign.Request.Edit -> navigate(PropertiesActivity::class.intent.setUUID(request.profile.uuid))
                        ProfilesDesign.Request.UpdateAll -> mutate("update-all") {
                            writeProfile {
                                queryAll().filter { it.imported && it.type != Profile.Type.File }
                                    .forEach { update(it.uuid) }
                            }
                        }
                        is ProfilesDesign.Request.Update -> mutate("update:${request.profile.uuid}") {
                            writeProfile { update(request.profile.uuid) }
                        }
                        is ProfilesDesign.Request.Delete -> mutate("delete:${request.profile.uuid}") {
                            writeProfile { delete(request.profile.uuid) }
                        }
                        is ProfilesDesign.Request.Active -> if (request.profile.imported) {
                            mutate("active:${request.profile.uuid}") { writeProfile { setActive(request.profile) } }
                        } else if (savePrompt?.isActive != true) {
                            savePrompt = launch { panel.requestSave(request.profile) }
                        }
                        is ProfilesDesign.Request.Duplicate -> mutate("clone:${request.profile.uuid}") {
                            val uuid = writeProfile { clone(request.profile.uuid) }
                            if (activityStarted) navigate(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                    }
                }
                if (activityStarted) ticker.onReceive { panel.updateElapsed() }
            }
        }
    }

    /** Writes run once; a lost reply must not replay clone/update operations. */
    private suspend fun <T> writeProfile(block: suspend IProfileManager.() -> T): T = withContext(Dispatchers.IO) {
        val remote = Remote.service.remote.get()
        try { remote.profile().block() }
        catch (error: DeadObjectException) { Remote.service.remote.reset(remote); throw error }
    }

    private suspend fun profileName(uuid: UUID): String = try {
        withTimeout(5_000L) { withProfile { queryByUUID(uuid)?.name } } ?: "配置"
    } catch (_: TimeoutCancellationException) { "配置" }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { "配置" }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if (uuid == null) return
        events.trySend(Event.ProfileChanged)
        launch {
            val name = profileName(uuid)
            if (activityStarted) design?.showToast(getString(R.string.toast_profile_updated_complete, name), ToastDuration.Long)
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (uuid == null) return
        launch {
            val name = profileName(uuid)
            if (activityStarted) design?.showActionDialog(
                getString(R.string.toast_profile_updated_failed, name, reason ?: "请稍后重试"), R.string.edit
            ) { startActivity(PropertiesActivity::class.intent.setUUID(uuid)) }
        }
    }
}
