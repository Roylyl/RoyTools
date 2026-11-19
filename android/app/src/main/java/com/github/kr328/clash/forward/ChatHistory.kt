package com.github.kr328.clash.forward

import android.content.Context
import org.json.JSONObject
import java.io.File

object ChatHistory {
    private fun file(c: Context): File {
        val p = TelegramForward.prefs(c)
        val bot = p.getString("token", "").orEmpty().substringBefore(':').filter(Char::isDigit)
        val chat = p.getString("chat", "").orEmpty().filter { it.isDigit() || it == '-' }
        return File(c.noBackupFilesDir, "telegram-chat/$bot-$chat.jsonl").also { it.parentFile?.mkdirs() }
    }
    @Volatile var revision=0L;private set
    @Synchronized fun clearAll(c:Context) {
        val directory=File(c.noBackupFilesDir,"telegram-chat")
        val prefs=TelegramForward.prefs(c)
        val marks=runCatching{JSONObject(prefs.getString("history_watermarks","{}").orEmpty())}.getOrDefault(JSONObject())
        directory.listFiles()?.filter{it.isFile && it.extension=="jsonl"}?.forEach { source ->
            val max=source.useLines{lines->lines.mapNotNull{runCatching{JSONObject(it).optLong("id")}.getOrNull()}.maxOrNull()} ?: 0L
            marks.put(source.name,maxOf(marks.optLong(source.name),max))
        }
        check(prefs.edit().putString("history_watermarks",marks.toString()).putLong("history_cleared_at",System.currentTimeMillis()/1000*1000).commit())
        if(directory.exists())check(directory.deleteRecursively()){"部分记录无法清除，请重试"}
        cachedPath="";cachedSize=-1;cachedTime=-1;cachedRecords=emptyList();revision++
    }
    private var cachedPath=""
    private var cachedSize=-1L
    private var cachedTime=-1L
    private var cachedRecords:List<JSONObject> = emptyList()
    @Synchronized fun read(c: Context): List<JSONObject> {
        val source=file(c)
        if(cachedPath!=source.path || cachedSize!=source.length() || cachedTime!=source.lastModified()) {
            cachedRecords=source.takeIf{it.exists()}?.useLines{lines->lines.mapNotNull{runCatching{JSONObject(it)}.getOrNull()}.toList()}.orEmpty()
            cachedPath=source.path;cachedSize=source.length();cachedTime=source.lastModified()
        }
        return cachedRecords
    }
    @Synchronized fun append(c: Context, message: JSONObject, incoming: Boolean, fallback: String = ""):Boolean {
        val id = message.optLong("message_id")
        val prefs=TelegramForward.prefs(c)
        val marks=runCatching{JSONObject(prefs.getString("history_watermarks","{}").orEmpty())}.getOrDefault(JSONObject())
        if(id<=marks.optLong(file(c).name) || message.optLong("date",System.currentTimeMillis()/1000)*1000<prefs.getLong("history_cleared_at",0))return false
        if (read(c).any { it.optLong("id") == id && it.optBoolean("incoming") == incoming }) return false
        val text = message.optString("text").ifBlank { message.optString("caption").ifBlank { fallback.ifBlank { if(message.has("photo")) "[图片]" else message.optJSONObject("document")?.optString("file_name")?.let { "[文件] $it" } ?: "[非文本消息]" } } }
        val record=JSONObject().put("id",id).put("incoming",incoming).put("text",text).put("time",message.optLong("date",System.currentTimeMillis()/1000)*1000)
        val photos=message.optJSONArray("photo")
        val video=message.optJSONObject("video")
        val document=message.optJSONObject("document")
        if(photos!=null && photos.length()>0){val image=photos.getJSONObject(photos.length()-1);record.put("media_type","photo").put("file_id",image.getString("file_id")).put("width",image.optInt("width")).put("height",image.optInt("height"))}
        else if(video!=null){record.put("media_type","video").put("file_id",video.getString("file_id")).put("width",video.optInt("width")).put("height",video.optInt("height"));record.put("thumb_id",video.optJSONObject("thumbnail")?.optString("file_id").orEmpty())}
        else if(document!=null){record.put("media_type","document").put("file_id",document.getString("file_id")).put("file_name",document.optString("file_name","文件")).put("mime",document.optString("mime_type","application/octet-stream")).put("file_size",document.optLong("file_size"))}
        file(c).appendText(record.toString()+"\n")
        return true
    }
}
