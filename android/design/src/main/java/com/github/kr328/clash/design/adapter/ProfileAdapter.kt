package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.model.ProfilePageState
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.Profile

class ProfileAdapter(
    private val context: Context,
    private val onClicked: (Profile) -> Unit,
    private val onMenuClicked: (Profile, View) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    class Holder(val binding: AdapterProfileBinding) : RecyclerView.ViewHolder(binding.root)

    var profiles: List<Profile> = emptyList()
    val states = ProfilePageState()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(AdapterProfileBinding.inflate(context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val profile = profiles[position]
        holder.binding.apply {
            this.profile = profile
            source = context.getString(if (profile.type == Profile.Type.File) {
                R.string.proxy_profiles_local_file
            } else R.string.proxy_profiles_subscription).let {
                if (!profile.imported) context.getString(R.string.format_type_unsaved, it) else it
            }
            root.isSelected = profile.active
            setClicked { if (!profile.active) onClicked(profile) }
            setMenu { anchor -> onMenuClicked(profile, anchor) }
        }
    }

    override fun getItemCount(): Int = profiles.size
}
