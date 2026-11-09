package com.github.kr328.clash.forward

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ChatUpload {
    fun send(c: Context, token: String, chat: String, uri: Uri, kind: String): JSONObject {
        require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]+")))
        val photo=kind=="photo"
        val limit = if(photo) 10L*1024*1024 else 50L*1024*1024
        var name = if(photo) "photo.jpg" else "file"
        c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),null,null,null)?.use {
            if(it.moveToFirst()) {
                name=it.getString(0).orEmpty().replace(Regex("[\\r\\n\\\"\\\\]"), "_").take(150)
                require(it.isNull(1) || it.getLong(1)<=limit) { "文件过大" }
            }
        }
        val boundary="RoyTools"+java.util.UUID.randomUUID().toString()
        val field=kind
        val conn=URL("https://api.telegram.org/bot$token/"+when(kind){"photo"->"sendPhoto";"video"->"sendVideo";else->"sendDocument"}).openConnection() as HttpURLConnection
        try {
            conn.requestMethod="POST"; conn.connectTimeout=15000;conn.readTimeout=60000;conn.doOutput=true;conn.setChunkedStreamingMode(16384)
            conn.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun write(text:String) { out.write(text.toByteArray(Charsets.UTF_8)) }
                write("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chat\r\n")
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\nContent-Type: ${c.contentResolver.getType(uri) ?: "application/octet-stream"}\r\n\r\n")
                val input=c.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                input.use { stream -> val bytes=ByteArray(16384); var total=0L
                    while(true) { val n=stream.read(bytes);if(n<0)break;total+=n;require(total<=limit){"文件过大"};out.write(bytes,0,n) }
                }
                write("\r\n--$boundary--\r\n")
            }
            val code=conn.responseCode
            val json=JSONObject((if(code in 200..299)conn.inputStream else conn.errorStream).bufferedReader().use{it.readText()})
            if(!json.optBoolean("ok"))throw TelegramForward.ApiError(code,0)
            return json.getJSONObject("result")
        } finally { conn.disconnect() }
    }
}
