package com.github.kr328.clash.log

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

suspend fun BaseActivity<*>.exportModuleLog(module: UnifiedLogs.Module) {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    val target = startActivityForResult(ActivityResultContracts.CreateDocument("text/plain"),
        "RoyTools-${module.title}日志-$stamp.txt") ?: return
    try {
        withContext(Dispatchers.IO) {
            val text = UnifiedLogs.exportText(this@exportModuleLog, module)
            val output = contentResolver.openOutputStream(target, "wt") ?: throw IOException("无法写入所选位置")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        Toast.makeText(this, "${module.title}日志已导出", Toast.LENGTH_SHORT).show()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RoyToolsDialogBuilder(this).setTitle("导出失败").setMessage(e.message ?: "无法写入所选位置")
            .setPositiveButton("知道了", null).show()
    }
}
