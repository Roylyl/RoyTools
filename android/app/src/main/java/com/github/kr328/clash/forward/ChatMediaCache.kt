package com.github.kr328.clash.forward

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ChatMediaCache {
    private val storageLock=Any()
    @Volatile private var epoch=0L
    fun generation():Long=epoch
    fun commit(generation:Long, action:()->Unit){synchronized(storageLock){check(generation==epoch){"媒体清理后已取消旧任务"};action()}}
    fun clearAll(c:Context){synchronized(storageLock){
        epoch++
        for(folder in listOf(File(c.cacheDir,"chat-media"),File(c.filesDir,"chat-downloads"))){
            if(folder.exists())check(folder.deleteRecursively()){"部分媒体无法清除，请重试"}
        }
    }}
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    suspend fun get(c:Context,id:String):File=withContext(Dispatchers.IO){
        val generation=epoch
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        locks.getOrPut(id){Mutex()}.withLock {
            val folder=File(c.cacheDir,"chat-media").apply{mkdirs()}
            val file=File(folder,id)
            if(!file.exists()) {
                val token=TelegramForward.prefs(c).getString("token", "").orEmpty()
                val result=TelegramForward.call(token,"getFile",JSONObject().put("file_id",id)).getJSONObject("result")
                val path=result.getString("file_path")
                require(path.matches(Regex("[A-Za-z0-9_./-]+")) && !path.contains(".."))
                val conn=URL("https://api.telegram.org/file/bot$token/$path").openConnection() as HttpURLConnection
                val temp=File.createTempFile("media", ".part",folder)
                try {
                    conn.connectTimeout=15000;conn.readTimeout=30000
                    check(conn.responseCode in 200..299)
                    conn.inputStream.use {input->temp.outputStream().use {out->
                        val bytes=ByteArray(16384);var total=0L
                        while(true){val n=input.read(bytes);if(n<0)break;total+=n;check(total<=50L*1024*1024);out.write(bytes,0,n)}
                    }}
                    commit(generation){check(temp.renameTo(file))}
                }finally{conn.disconnect();temp.delete()}
            }
            file
        }
    }
    fun bitmap(file:File):android.graphics.Bitmap? {
        val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(file.path,options)
        options.inSampleSize=1
        while(options.outWidth/options.inSampleSize>1600||options.outHeight/options.inSampleSize>1600) options.inSampleSize*=2
        options.inJustDecodeBounds=false
        return android.graphics.BitmapFactory.decodeFile(file.path,options)
    }
}
