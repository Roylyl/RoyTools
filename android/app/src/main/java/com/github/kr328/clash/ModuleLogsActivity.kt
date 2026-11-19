package com.github.kr328.clash

import com.github.kr328.clash.design.LogcatDesign
import com.github.kr328.clash.log.UnifiedLogs
import com.github.kr328.clash.log.exportModuleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ticker

class ModuleLogsActivity : BaseActivity<LogcatDesign>() {
    companion object { const val EXTRA_MODULE = "roytools_log_module" }

    override suspend fun main() {
        val module = runCatching { UnifiedLogs.Module.valueOf(intent.getStringExtra(EXTRA_MODULE) ?: "App") }
            .getOrDefault(UnifiedLogs.Module.App)
        val design = LogcatDesign(this, streaming = true, moduleName = module.title)
        setContentDesign(design)
        UnifiedLogs.start(this)
        val updates = ticker(1000, 0)
        try {
            while (isActive) select<Unit> {
                if (activityStarted) updates.onReceive {
                    design.patchMessages(withContext(Dispatchers.IO) { UnifiedLogs.read(this@ModuleLogsActivity, module) })
                }
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        LogcatDesign.Request.Export -> exportModuleLog(module)
                        LogcatDesign.Request.Close, LogcatDesign.Request.History -> finish()
                        else -> Unit
                    }
                }
            }
        } finally { updates.cancel() }
    }
}
