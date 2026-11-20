package com.github.kr328.clash.design

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfilesDesign(context: Context) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object ImportSubscription : Request()
        object ImportFile : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Delete(val profile: Profile) : Request()
    }

    private val binding = DesignProfilesBinding.inflate(context.layoutInflater, context.root, false)
    private val adapter = ProfileAdapter(context, this::requestActive, this::showOverflow)
    private val list = binding.mainList.recyclerList.apply { layoutManager = LinearLayoutManager(context) }
    private val current = context.layoutInflater.inflate(R.layout.proxy_profiles_current, list, false)
    private val management = context.layoutInflater.inflate(R.layout.proxy_profiles_management, list, false)
    private val updateAll = management.findViewById<View>(R.id.update_subscriptions)

    override val root: View
        get() = binding.root

    suspend fun patchProfiles(profiles: List<Profile>) {
        adapter.patchDataSet(adapter::profiles, profiles, id = { it.uuid })

        withContext(Dispatchers.Main) {
            val active = profiles.firstOrNull { it.active && it.imported }
            current.findViewById<TextView>(R.id.profile_current_name).text =
                active?.name ?: context.getString(R.string.proxy_profiles_not_imported)
            current.findViewById<View>(R.id.profile_current_source_row).visibility =
                if (active == null) View.GONE else View.VISIBLE
            if (active != null) {
                current.findViewById<TextView>(R.id.profile_current_source).setText(
                    if (active.type == Profile.Type.File) R.string.proxy_profiles_local_file
                    else R.string.proxy_profiles_subscription
                )
            }
            current.findViewById<View>(R.id.saved_profiles_heading).visibility =
                if (profiles.isEmpty()) View.GONE else View.VISIBLE
            updateAll.visibility = if (profiles.any(::isUpdatable)) View.VISIBLE else View.GONE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showActionDialog(context.getString(R.string.active_unsaved_tips), R.string.edit) {
            requests.trySend(Request.Edit(profile))
        }
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        current.findViewById<TextView>(R.id.profile_current_name).setText(R.string.proxy_profiles_not_imported)
        management.findViewById<View>(R.id.import_subscription).setOnClickListener {
            requests.trySend(Request.ImportSubscription)
        }
        management.findViewById<View>(R.id.import_configuration).setOnClickListener {
            requests.trySend(Request.ImportFile)
        }
        updateAll.setOnClickListener { requestUpdateAll() }

        list.bindAppBarElevation(binding.activityBarLayout)
        list.adapter = ConcatAdapter(FixedRow(current), adapter, FixedRow(management))
    }

    private fun isUpdatable(profile: Profile) = profile.imported && profile.type != Profile.Type.File

    private fun showOverflow(profile: Profile, anchor: View) {
        PopupMenu(context, anchor, Gravity.END, 0, R.style.RoyTools_AnchoredMenu).apply {
            if (isUpdatable(profile)) {
                menu.add(R.string.update).setOnMenuItemClickListener {
                    requests.trySend(Request.Update(profile))
                    true
                }
            }
            menu.add(R.string.edit).setOnMenuItemClickListener {
                requests.trySend(Request.Edit(profile))
                true
            }
            val deleteTitle = SpannableString(context.getText(R.string.delete)).apply {
                val color = context.resolveThemedColor(com.google.android.material.R.attr.colorError)
                setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            menu.add(deleteTitle).setOnMenuItemClickListener {
                confirmDelete(profile)
                true
            }
            show()
        }
    }

    private fun requestUpdateAll() {
        if (adapter.states.allUpdating) return
        adapter.states.allUpdating = true
        updateAll.isEnabled = false
        updateAll.alpha = 0.55f
        requests.trySend(Request.UpdateAll)
    }

    fun finishUpdateAll() {
        adapter.states.allUpdating = false
        updateAll.isEnabled = true
        updateAll.alpha = 1f
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    private fun confirmDelete(profile: Profile) {
        RoyToolsDialogBuilder(context)
            .setTitle(R.string.proxy_profiles_delete_title)
            .setMessage(context.getString(R.string.proxy_profiles_delete_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> requests.trySend(Request.Delete(profile)) }
            .show()
    }

    private class FixedRow(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(view) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = 1
    }
}
