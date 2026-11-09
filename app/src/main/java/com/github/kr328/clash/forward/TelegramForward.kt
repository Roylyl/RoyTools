package com.github.kr328.clash.forward

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object TelegramForward {
    fun prefs(c: Context) = c.getSharedPreferences("notification_forward_private", Context.MODE_PRIVATE)
    fun call(token: String, method: String, body: JSONObject): JSONObject {
        require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) { "Bot Token格式不正确" }
        val connection = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) throw ApiError(code, result.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0)
            return result
        } finally { connection.disconnect() }
    }
    class ApiError(val code: Int, val retryAfter: Int): Exception(when(code) {
        401 -> "Token无效，请检查BotFather提供的Token"
        403 -> "机器人无法发送，请先在Telegram中启动机器人并取消屏蔽"
        409 -> "该机器人已被其他程序或Webhook使用，请使用独立机器人"
        429 -> "发送过于频繁，正在等待重试"
        else -> "Telegram请求失败（$code），请检查配置后重试"
    })
}

class ForwardListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); ChatReceiver.start(this) }
    private val recent = LinkedHashMap<String, String>()
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = TelegramForward.prefs(this)
        if (!p.getBoolean("enabled", false)) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.packageName.startsWith("org.telegram.") ||
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val content = "$title\n$body"
        if (recent[sbn.key] == content) return
        recent[sbn.key] = content
        if (recent.size > 200) recent.remove(recent.keys.first())
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val message = "$app\n$content".take(3900)
        val job = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(workDataOf("message" to message, "created" to System.currentTimeMillis(), "generation" to p.getLong("generation", 0)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("notification-forward").build()
        WorkManager.getInstance(this).enqueueUniqueWork("notification-forward-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, job)
    }
}

class ForwardWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork(): Result {
        val p = TelegramForward.prefs(applicationContext)
        if (!p.getBoolean("enabled", false) || p.getLong("generation", 0) != inputData.getLong("generation", -1)) return Result.success()
        if (System.currentTimeMillis() - inputData.getLong("created", 0) > TimeUnit.HOURS.toMillis(24)) return Result.success()
        return try {
            val sent = TelegramForward.call(p.getString("token", "").orEmpty(), "sendMessage", JSONObject()
                .put("chat_id", p.getString("chat", "")).put("text", inputData.getString("message")))
            runCatching { ChatHistory.append(applicationContext, sent.getJSONObject("result"), false, inputData.getString("message").orEmpty()) }
            p.edit().putString("status", "最近一次转发成功").apply()
            Result.success()
        } catch (e: Exception) {
            p.edit().putString("status", if (e is TelegramForward.ApiError) e.message else "连接Telegram失败，请检查网络，将自动重试").apply()
            if (e is TelegramForward.ApiError && e.code in listOf(400,401,403)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
