package com.github.kr328.clash

import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive

class AppSettingsActivity : BaseActivity<AppSettingsDesign>() {
    override suspend fun main() {
        setContentDesign(AppSettingsDesign(this, ServiceStore(this), clashRunning))
        while (isActive) {
            when (events.receive()) {
                Event.ClashStart, Event.ClashStop, Event.ServiceRecreated -> recreate()
                else -> Unit
            }
        }
    }
}