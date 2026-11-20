package com.github.kr328.clash

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import com.github.kr328.clash.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    companion object {
        const val EXTRA_COMPACT_EDITOR = "roytools_compact_profile_editor"
    }

    private var canceled: Boolean = false
    private var replacementFile: Uri? = null
    private lateinit var original: Profile

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val compact = intent.getBooleanExtra(EXTRA_COMPACT_EDITOR, false)
        if (compact) setTitle(R.string.proxy_profiles_edit)
        val design = PropertiesDesign(this).apply {
            compactEditor = compact
        }

        original = withProfile { queryByUUID(uuid) } ?: return finish()

        design.profile = original

        setContentDesign(design)

        defer {
            canceled = true
            try {
                withProfile { release(uuid) }
            } catch (error: Exception) {
                // Failed exit leaves this editor open; do not disable later draft saves.
                canceled = false
                throw error
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            finish()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.ReplaceFile -> {
                            if (design.profile.type == Profile.Type.File) {
                                try {
                                    val file = startActivityForResult(ActivityResultContracts.GetContent(), "*/*")
                                    if (file != null) {
                                        replacementFile = file
                                        design.showReplacementFile(file.fileName ?: getString(R.string.proxy_profiles_local_file))
                                    }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    design.showExceptionToast(error)
                                }
                            }
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        design?.apply {
            launch {
                if (!progressing) {
                    if ((original == profile && replacementFile == null) || requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            else -> {
                try {
                    withProcessing { updateStatus ->
                        withProfile {
                            patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                            replacementFile?.let { file ->
                                FilesClient(this@PropertiesActivity).copyDocument("${profile.uuid}/config.yaml", file)
                            }

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch {
                                        updateStatus(it)
                                    }
                                }
                            }
                        }
                    }

                    setResult(RESULT_OK)

                    finish()
                } catch (e: Exception) {
                    showExceptionToast(e)
                }
            }
        }
    }
}
