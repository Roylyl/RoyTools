package com.mahmutalperenunal.adaptivehz

import android.app.*
import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import com.github.kr328.clash.common.log.RefreshLog as Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Observer
import moe.shizuku.manager.adb.*
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WirelessSetupService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var pairPort = -1
    private var connectPort = -1
    private var pairDiscovery: AdbMdns? = null
    private var connectDiscovery: AdbMdns? = null
    private var pairing = false
    private val prefs by lazy { getSharedPreferences("wireless_setup", MODE_PRIVATE) }
    // The name is the public-key comment shown by Android; keep the existing RSA key store.
    private val key by lazy { AdbKey(PreferenceAdbKeyStore(prefs), "RoyTools") }

    override fun onCreate() {
        super.onCreate()
        endpointListener = { port ->
            main.post {
                if (connectPort != port) {
                    connectPort = port
                    prefs.edit().putInt("connect_port", port).apply()
                    if (prefs.getBoolean("paired", false)) connect()
                }
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "激活进度", NotificationManager.IMPORTANCE_LOW).apply {
            description = "检测到无线调试配对服务后，直接在通知中输入配对码。"
        })
        manager.createNotificationChannel(NotificationChannel(
            PAIR_CHANNEL, "配对码横幅", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "检测到配对码窗口时显示横幅，可直接输入6位配对码。"
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        startForeground(ID, notification("正在寻找无线调试", "请在系统中开启无线调试，并选择“使用配对码配对设备”。"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PORT) {
            val port = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("port")?.toString()?.trim()?.toIntOrNull()
            if (port == null || port !in 1..65535) {
                notifyPort()
            } else {
                connectPort = port
                prefs.edit().putInt("connect_port", port).apply()
                getSystemService(NotificationManager::class.java).cancel(PAIR_ID)
                connect()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CODE) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("code")?.toString()?.trim()
            val submittedPort = intent.getIntExtra("port", -1)
            if (code == null || !code.matches(Regex("[0-9]{6}"))) {
                notify("请输入6位配对码", "请使用当前系统窗口显示的配对码。", input = pairPort > 0)
            } else if (submittedPort != pairPort || pairPort <= 0) {
                notify("配对窗口已变化", "请重新打开配对码窗口，再输入当前配对码。")
            } else {
                pair(code, submittedPort)
            }
            return START_NOT_STICKY
        }
        if (Shizuku.pingBinder()) {
            finish("辅助服务已运行", "可以直接使用刷新率控制。")
            return START_NOT_STICKY
        }
        pairing = intent?.action != ACTION_CONNECT

        connectPort = prefs.getInt("connect_port", -1)
        startDiscovery()
        if (connectPort > 0 && prefs.getBoolean("paired", false)) connect()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 600_000)
        main.postDelayed({
            if (!Shizuku.pingBinder() && prefs.getBoolean("paired", false) && connectPort <= 0) notifyPort()
        }, 8000)
        return START_NOT_STICKY
    }

    private val timeout = Runnable {
        finish("暂未完成激活", "请回到应用重试。需要保持无线调试开启，并连接Wi-Fi。")
    }

    private fun startDiscovery() {
        if (connectDiscovery == null) {
            connectDiscovery = AdbMdns(this, "_adb-tls-connect._tcp", Observer { port ->
                main.post {
                    connectPort = port
                    if (port > 0) prefs.edit().putInt("connect_port", port).apply()
                    if (port > 0 && prefs.getBoolean("paired", false)) connect()
                }
            }).also { it.start() }
        }
        if (pairing && !prefs.getBoolean("paired", false) && pairDiscovery == null) {
            pairDiscovery = AdbMdns(this, "_adb-tls-pairing._tcp", Observer { port ->
                main.post {
                    if (port > 0 && port != pairPort) {
                        pairPort = port

                        notify("输入无线调试配对码", "输入系统窗口中的6位配对码，完成后自动激活。", input = true)
                    }
                    // A transient mDNS loss must not replace the notification or interrupt typing.
                }
            }).also { it.start() }
        }
    }

    private fun pair(code: String, port: Int) {
        if (!busy.compareAndSet(false, true)) return
        getSystemService(NotificationManager::class.java).cancel(PAIR_ID)
        notify("正在配对", "请保持系统配对码窗口打开。")
        worker.execute {
            val result = runCatching {
                AdbPairingClient("127.0.0.1", port, code, key).use {
                    check(it.start()) { "配对码错误或已过期" }
                }
                prefs.edit().putBoolean("paired", true).apply()
            }
            main.post {
                busy.set(false)
                result.onSuccess {
                    notify("配对成功", "正在连接并启动内置辅助服务。")
                    main.postDelayed({
                        if (!Shizuku.pingBinder() && connectPort <= 0) notifyPort()
                    }, 3000)
                    pairDiscovery?.stop()
                    pairDiscovery = null
                    if (connectPort > 0) {
                        connect()
                    } else {
                        connectDiscovery?.stop()
                        connectDiscovery = null
                        main.postDelayed({ startDiscovery() }, 700)
                    }
                }.onFailure {
                    Log.e("WirelessActivation", "Pairing failed", it)
                    notify("配对未成功", "请重新打开配对码窗口，输入新的配对码。", input = pairPort > 0)
                }
            }
        }
    }

    private fun connect() {
        if (connectPort <= 0 || !busy.compareAndSet(false, true)) return
        val port = connectPort
        worker.execute {
            val result = runCatching {
                val pkg = packageName
                val apk = applicationInfo.sourceDir
                val command = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; " +
                    "pm grant $pkg android.permission.POST_NOTIFICATIONS; " +
                    "dumpsys deviceidle whitelist +$pkg; " +
                    "appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                    "CLASSPATH='$apk' app_process /system/bin --nice-name=adaptivehz_server " +
                    "rikka.shizuku.server.ShizukuService >/data/local/tmp/adaptivehz-backend.log 2>&1 </dev/null &"
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.shellCommand(command, null)
                }
            }
            main.post {
                busy.set(false)
                result.onSuccess { waitForBackend(0) }.onFailure {
                    Log.e("WirelessActivation", "Connect failed", it)
                    connectPort = -1
                    prefs.edit().remove("connect_port").apply()
                    notifyPort()
                }
            }
        }
    }

    private fun waitForBackend(attempt: Int) {
        if (Shizuku.pingBinder()) {
            OneSetup.configure(this)
            finish("激活成功", "权限已配置，可回到应用选择刷新率模式。")
        } else if (attempt < 15) {
            main.postDelayed({ waitForBackend(attempt + 1) }, 500)
        } else {
            notify("辅助服务尚未连上", "请回到应用重试，或查看电脑激活方式。")
        }
    }

    private fun notification(title: String, message: String, input: Boolean = false, portInput: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, if (input || portInput) PAIR_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_tile_adaptive_hz)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setColor(android.graphics.Color.rgb(0, 122, 255))
            .setContentIntent(open).setOnlyAlertOnce(!input && !portInput).setOngoing(!input && !portInput)
            .setPriority(if (input || portInput) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (input || portInput) {
            val reply = Intent(this, WirelessSetupService::class.java)
                .setAction(if (portInput) ACTION_PORT else ACTION_CODE).putExtra("port", pairPort)
            val pending = PendingIntent.getService(this, if (portInput) 1 else pairPort, reply,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, if (portInput) "输入连接端口" else "输入配对码", pending)
                .addRemoteInput(RemoteInput.Builder(if (portInput) "port" else "code").setLabel(if (portInput) "无线调试首页的端口号" else "6位配对码")
                    .setAllowFreeFormInput(true).build()).build())
        }
        return builder.build()
    }

    private fun notifyPort() {
        getSystemService(NotificationManager::class.java).notify(PAIR_ID,
            notification("已配对，请确认连接端口", "保持无线调试首页打开。如果未自动连接，请输入IP地址冒号后面的端口号。",
                portInput = true))
    }

    private fun notify(title: String, message: String, input: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(if (input) PAIR_ID else ID, notification(title, message, input))
    }

    private fun finish(title: String, message: String) {
        getSystemService(NotificationManager::class.java).cancel(PAIR_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val result = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_tile_adaptive_hz)
            .setContentTitle(title).setContentText(message).setAutoCancel(true)
            .setColor(android.graphics.Color.rgb(0, 122, 255)).build()
        getSystemService(NotificationManager::class.java).notify(ID + 1, result)
        stopSelf()
    }

    override fun onDestroy() {
        endpointListener = null
        main.removeCallbacksAndMessages(null)
        pairDiscovery?.stop()
        connectDiscovery?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        @Volatile private var endpointListener: ((Int) -> Unit)? = null

        fun observeWirelessSettings(root: android.view.accessibility.AccessibilityNodeInfo?) {
            val listener = endpointListener ?: return
            if (root == null) return
            val texts = mutableListOf<String>()
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 100) {
                val node = queue.removeFirst()
                node.text?.toString()?.let { texts.add(it) }
                for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
            }
            if (texts.none { it == "无线调试" || it == "Wireless debugging" }) return
            if (texts.any { it == "WLAN 配对码" || it == "Wi-Fi pairing code" || it == "与设备配对" }) return
            val pattern = Regex("""[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}:([0-9]{1,5})""")
            val port = texts.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (port != null && port in 1..65535) listener(port)
        }
        private const val CHANNEL = "wireless_activation_progress"
        private const val PAIR_CHANNEL = "wireless_pairing_heads_up"
        private const val PAIR_ID = 2003
        private const val ID = 2001
        const val ACTION_PORT = "wireless_connect_port"
        const val ACTION_CODE = "wireless_pair_code"
        const val ACTION_CONNECT = "wireless_connect"
    }
}









