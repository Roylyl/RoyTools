package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.util.*

class LogcatReader(context: Context, file: LogFile) : AutoCloseable {
    private val source = context.logsDir.resolve(file.fileName)

    override fun close() {
        // Each streaming pass owns and closes its reader.
    }

    fun readAll(): List<LogMessage> {
        return buildList { forEachMessage { add(it) } }
    }

    fun forEachMessage(consume: (LogMessage) -> Unit) {
        LegacyLogParser.forEach(source) { entry ->
            consume(LogMessage(LogMessage.Level.valueOf(entry.level), entry.message, Date(entry.time)))
        }
    }
}
