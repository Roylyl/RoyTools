package com.github.kr328.clash

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.NewProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfileProvider
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.common.constants.Authorities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.QRResult.QRError
import io.github.g00fy2.quickie.QRResult.QRMissingPermission
import io.github.g00fy2.quickie.QRResult.QRSuccess
import io.github.g00fy2.quickie.QRResult.QRUserCanceled
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*

class NewProfileActivity : BaseActivity<NewProfileDesign>() {
    private val self: NewProfileActivity
        get() = this

    private val scanLauncher = registerForActivityResult(ScanQRCode(), ::scanResultHandler)

    override suspend fun main() {
        val design = NewProfileDesign(this)

        design.patchProviders(queryProfileProviders())

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        is NewProfileDesign.Request.Create -> {
                            when (val provider = it.provider) {
                                is ProfileProvider.File -> createDraft(Profile.Type.File)
                                is ProfileProvider.Url -> createDraft(Profile.Type.Url)
                                is ProfileProvider.External -> provider.get()?.let { (uri, name) ->
                                    createDraft(Profile.Type.External, uri.toString(), name)
                                }
                                is ProfileProvider.QR -> Unit
                            }
                        }
                        is NewProfileDesign.Request.OpenDetail -> {
                            launchAppDetailed(it.provider)
                        }

                        is NewProfileDesign.Request.LaunchScanner -> {
                            scanLauncher.launch(null)
                        }
                    }
                }
            }
        }
    }

    private fun launchAppDetailed(provider: ProfileProvider.External) {
        val data = Uri.fromParts(
            "package",
            provider.intent.component?.packageName ?: return,
            null
        )

        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(data))
    }

    private suspend fun launchProperties(uuid: UUID) {
        val r = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            PropertiesActivity::class.intent.setUUID(uuid)
        )

        if (r.resultCode == Activity.RESULT_OK)
            finish()
    }

    private suspend fun ProfileProvider.External.get(): Pair<Uri, String?>? {
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            intent
        )

        if (result.resultCode != RESULT_OK)
            return null

        val uri = result.data?.data
        val name = result.data?.getStringExtra(Intents.EXTRA_NAME)

        if (uri != null) {
            return uri to name
        }

        return null
    }

    private suspend fun queryProfileProviders(): List<ProfileProvider> {
        return withContext(Dispatchers.IO) {
            val providers = packageManager.queryIntentActivities(
                Intent(Intents.ACTION_PROVIDE_URL),
                0
            ).map {
                val activity = it.activityInfo

                val name = activity.applicationInfo.loadLabel(packageManager)
                val summary = activity.loadLabel(packageManager)
                val icon = activity.loadIcon(packageManager)
                val intent = Intent(Intents.ACTION_PROVIDE_URL)
                    .setComponent(
                        ComponentName(
                            activity.packageName,
                            activity.name
                        )
                    )

                ProfileProvider.External(name.toString(), summary.toString(), icon, intent)
            }

            listOf(
                ProfileProvider.File(self),
                ProfileProvider.Url(self),
                ProfileProvider.QR(self)
            ) + providers
        }
    }

    private fun scanResultHandler(result: QRResult) {
        lifecycleScope.launch {
            when (result) {
                is QRSuccess -> {
                    val url = result.content.rawValue
                        ?: result.content.rawBytes?.let { String(it) }.orEmpty()

                    createProfileByQrCode(url)
                }

                QRUserCanceled -> {}
                QRMissingPermission -> design?.showExceptionToast(getString(R.string.import_from_qr_no_permission))
                is QRError -> design?.showExceptionToast(getString(R.string.import_from_qr_exception))
            }
        }
    }

    private suspend fun createProfileByQrCode(url: String) {
        createDraft(Profile.Type.Url, url)
    }

    private var creating: Job? = null

    private fun createDraft(type: Profile.Type, source: String = "", label: String? = null) {
        if (creating?.isActive == true) return
        creating = launch {
            try {
                val manager = withTimeout(8000L) {
                    // Acquiring the provider starts the profile process even after an OEM
                    // task cleaner has rejected a background service bind.
                    withContext(Dispatchers.IO) {
                        contentResolver.acquireUnstableContentProviderClient(Authorities.STATUS_PROVIDER)?.close()
                    }
                    Remote.service.bind()
                    Remote.service.remote.get().profile()
                }
                val name = label ?: getString(R.string.new_profile)
                val uuid = withContext(Dispatchers.IO) { manager.create(type, name, source) }
                launchProperties(uuid)
            } catch (_: TimeoutCancellationException) {
                design?.showExceptionToast("配置服务未连接，请再点一次重试")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                design?.showExceptionToast(error)
            }
        }
    }
}