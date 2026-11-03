package com.github.kr328.clash.design

import android.content.Context
import android.content.Intent
import android.view.View
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.switch
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendBroadcastSelf

class AppSettingsDesign(context: Context, srvStore: ServiceStore, running: Boolean) : Design<Unit>(context) {
    private val binding = DesignSettingsCommonBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        val screen = preferenceScreen(context) {
            category(R.string.service)
            switch(value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary) {
                listener = OnChangedListener {
                    // Switch persists its value before notifying its listener.
                    context.sendBroadcastSelf(Intent(Intents.ACTION_NOTIFICATION_CHANGED))
                }
            }
        }
        binding.content.addView(screen.root)
    }
}
