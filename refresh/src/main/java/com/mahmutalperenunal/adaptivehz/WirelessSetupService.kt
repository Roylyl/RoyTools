package com.mahmutalperenunal.adaptivehz

import android.app.*
import android.content.*
import android.os.*
import com.github.kr328.clash.common.log.RefreshLog as Log
import com.github.kr328.clash.common.util.NotificationGroups
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Observer
import moe.shizuku.manager.adb.*
import rikka.shizuku.Shizuku
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import javax.net.ssl.SSLException

class WirelessSetupService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var destroyed = false
    private fun postIfActive(action: () -> Unit) {
        main.post { if (!destroyed && AdaptiveHzPrefs.isControlsEnabled(this)) action() }
    }
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
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) {
            cancelForDisabledMode(this)
            stopSelf()
            return
        }
        endpointListener = { port ->
            postIfActive {
                if (connectPort != port) {
                    connectPort = port
                    prefs.edit().putInt("connect_port", port).apply()
                    Log.d(TAG, "Connection endpoint observed in settings: port=$port")
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
        manager.cancel(LEGACY_PAIR_ID)
        manager.cancel(ID + 1)
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
        startForeground(ID, notification("正在寻找无线调试", "请在系统中开启无线调试，并选择“使用配对码配对设备”。"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) {
            cancelForDisabledMode(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PORT) {
            val port = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("port")?.toString()?.trim()?.toIntOrNull()
            if (port == null || port !in 1..65535) {
                notifyPort()
            } else {
                connectPort = port
                prefs.edit().putInt("connect_port", port).apply()
                cancelPairNotification()
                notify("正在连接辅助服务", "正在使用当前无线调试端口连接，请稍候。")
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
                pairing = true
                startDiscovery()
                notify("配对窗口已变化", "请保持当前配对码窗口打开，再输入当前配对码。", input = pairPort > 0)
            } else {
                pair(code, submittedPort)
            }
            return START_NOT_STICKY
        }
        if (Shizuku.pingBinder() && hasWritePermission()) {
            waitForBackend(0)
            return START_NOT_STICKY
        }
        pairing = intent?.action != ACTION_CONNECT

        connectPort = prefs.getInt("connect_port", -1)
        Log.d(TAG, "Activation requested: pairing=$pairing paired=${prefs.getBoolean("paired", false)} cachedPort=$connectPort")
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
                postIfActive {
                    // mDNS can briefly report a loss while resolving a replacement.
                    // Keep the last endpoint until a connection to that endpoint fails.
                    if (port > 0) {
                        connectPort = port
                        prefs.edit().putInt("connect_port", port).apply()
                        Log.d(TAG, "Connection endpoint discovered: port=$port")
                        if (prefs.getBoolean("paired", false)) connect()
                    }
                }
            }).also { it.start() }
        }
        // A previous successful pairing is only a hint. Android may have forgotten
        // this key, so an explicit activation must still discover new pairing windows.
        if (pairing && pairDiscovery == null) {
            pairDiscovery = AdbMdns(this, "_adb-tls-pairing._tcp", Observer { port ->
                postIfActive {
                    if (port > 0 && port != pairPort) {
                        pairPort = port
                        Log.d(TAG, "Pairing endpoint discovered: port=$port")
                        notify("输入无线调试配对码", "输入系统窗口中的6位配对码，完成后自动激活。", input = true)
                    }
                    // A transient mDNS loss must not replace the notification or interrupt typing.
                }
            }).also { it.start() }
        }
    }

    private fun pair(code: String, port: Int) {
        if (!busy.compareAndSet(false, true)) {
            notify("正在处理上一次连接", "请保持配对码窗口打开，稍后再输入当前配对码。", input = pairPort > 0)
            return
        }
        Log.d(TAG, "Pairing started: port=$port")
        cancelPairNotification()
        notify("正在配对", "请保持系统配对码窗口打开。")
        worker.execute {
            val result = runCatching {
                AdbPairingClient("127.0.0.1", port, code, key).use {
                    check(it.start()) { "配对码错误或已过期" }
                }
                prefs.edit().putBoolean("paired", true).apply()
            }
            postIfActive {
                busy.set(false)
                result.onSuccess {
                    pairing = false
                    Log.d(TAG, "Pairing completed; connecting to backend endpoint: port=$connectPort")
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
                    Log.e(TAG, "Pairing failed: port=$port", it)
                    notify("配对未成功", "请重新打开配对码窗口，输入新的配对码。", input = pairPort > 0)
                }
            }
        }
    }

    private fun connect() {
        if (connectPort <= 0 || !busy.compareAndSet(false, true)) return
        val port = connectPort
        Log.d(TAG, "ADB connection started: port=$port")
        worker.execute {
            val result = runCatching {
                val pkg = packageName
                val apk = applicationInfo.sourceDir
                val permissionCommand = "id; id -Z; pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; " +
                    "pm grant $pkg android.permission.POST_NOTIFICATIONS; " +
                    "dumpsys deviceidle whitelist +$pkg; " +
                    "appops set $pkg RUN_ANY_IN_BACKGROUND allow"
                Log.d(TAG, "ADB permission setup started: port=$port")
                val permissionOutput = executeShell(port, permissionCommand, MAX_PERMISSION_OUTPUT, captureTail = true)
                val permissionSummary = permissionOutput.substringBefore(OUTPUT_TAIL_MARKER).lineSequence().map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("at ") && !it.startsWith("...") }
                    .take(4).joinToString("\n").take(600)
                if (permissionSummary.isNotEmpty()) {
                    if (SHELL_FAILURE.containsMatchIn(permissionSummary)) Log.w(TAG, "ADB permission setup: $permissionSummary")
                    else Log.d(TAG, "ADB permission setup: $permissionSummary")
                }
                permissionOutput.substringAfter(OUTPUT_TAIL_MARKER, "").takeIf { it.isNotBlank() }?.let {
                    Log.w(TAG, "ADB permission output tail: $it")
                }
                val launchCommand = "echo ROYTOOLS_BACKEND_START; " +
                    "CLASSPATH='$apk' /system/bin/setsid /system/bin/app_process /system/bin --nice-name=adaptivehz_server " +
                    "rikka.shizuku.server.ShizukuService >/data/local/tmp/adaptivehz-backend.log 2>&1 </dev/null & " +
                    "echo ROYTOOLS_BACKEND_LAUNCHER_PID:${'$'}!; sleep 0.6; " +
                    "echo ROYTOOLS_BACKEND_PS; ps -A -o PID,NAME | grep '[a]daptivehz_server' || true; " +
                    "echo ROYTOOLS_BACKEND_LOG_TAIL; tail -c 1536 /data/local/tmp/adaptivehz-backend.log 2>&1"
                Log.d(TAG, "ADB backend launch started: port=$port")
                val launchOutput = executeShell(port, launchCommand, MAX_SHELL_OUTPUT, keepTail = true)
                if (launchOutput.isNotEmpty()) {
                    if (SHELL_FAILURE.containsMatchIn(launchOutput)) Log.w(TAG, "ADB backend launch: $launchOutput")
                    else Log.d(TAG, "ADB backend launch: $launchOutput")
                }
                permissionSummary to launchOutput
            }
            postIfActive {
                busy.set(false)
                result.onSuccess { (permissionSummary, launchOutput) ->
                    Log.d(TAG, "Backend launch command sent: port=$port")
                    val failure = launchOutput.takeIf { SHELL_FAILURE.containsMatchIn(it) }
                        ?: permissionSummary.takeIf { SHELL_FAILURE.containsMatchIn(it) }
                    val failureSummary = failure?.lineSequence()?.map { it.trim() }
                        ?.firstOrNull { it.isNotBlank() && !it.startsWith("at ") && !it.startsWith("...") && SHELL_FAILURE.containsMatchIn(it) }
                    waitForBackend(0, failureSummary?.take(240))
                }.onFailure {
                    Log.e(TAG, "Connect failed: attemptedPort=$port currentPort=$connectPort", it)
                    if (isRejectedWirelessAdbCredential(it)) {
                        // Android can forget an authorized public key independently of
                        // our preferences. Re-pair the same key; never delete RSA state.
                        prefs.edit().putBoolean("paired", false).apply()
                        pairing = true
                        Log.w(TAG, "ADB TLS credential rejected; waiting for a new pairing window")
                        cancelPairNotification()
                        startDiscovery()
                        notify("需要重新配对", "系统已不再认可此前的无线调试授权，请打开配对码窗口并输入当前配对码。", input = pairPort > 0)
                    } else if (connectPort > 0 && connectPort != port) {
                        // Discovery/settings may have delivered a fresh endpoint while
                        // this attempt was busy. Its failure must not erase the new one.
                        Log.d(TAG, "Retrying newer connection endpoint: port=$connectPort")
                        connect()
                    } else {
                        connectPort = -1
                        if (prefs.getInt("connect_port", -1) == port) {
                            prefs.edit().remove("connect_port").apply()
                        }
                        notifyPort()
                    }
                }
            }
        }
    }

    /** Each phase owns a TLS connection; the ADB stream is drained even after log limits. */
    private fun executeShell(port: Int, command: String, limit: Int, keepTail: Boolean = false, captureTail: Boolean = false): String {
        val output = StringBuilder()
        val tail = StringBuilder()
        var truncated = false
        AdbClient("127.0.0.1", port, key).use { client ->
            client.connect()
            client.shellCommand(command) { bytes ->
                val text = String(bytes, Charsets.UTF_8)
                if (captureTail) {
                    tail.append(text)
                    if (tail.length > MAX_PERMISSION_TAIL) tail.delete(0, tail.length - MAX_PERMISSION_TAIL)
                }
                if (keepTail) {
                    output.append(text)
                    if (output.length > limit) output.delete(0, output.length - limit)
                } else {
                    val available = (limit - output.length).coerceAtLeast(0)
                    if (text.length > available) truncated = true
                    output.append(text.take(available))
                }
            }
        }
        return output.toString().trim() + if (captureTail && truncated) OUTPUT_TAIL_MARKER + tail.toString().trim() else ""
    }

    private fun hasWritePermission(): Boolean = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun waitForBackend(attempt: Int, commandFailure: String? = null) {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "Backend binder connected")
            if (!hasWritePermission()) {
                Log.w(TAG, "Backend connected without WRITE_SECURE_SETTINGS permission")
                notify("辅助服务已连接，尚缺系统设置权限", "请在开发者选项开启“USB调试（安全设置）”，然后返回RoyTools重试激活。")
                return
            }
            OneSetup.configure(this)
            finish("激活成功", "权限已配置，可回到应用选择刷新率模式。")
        } else if (attempt < 15) {
            main.postDelayed({ waitForBackend(attempt + 1, commandFailure) }, 500)
        } else {
            Log.w(TAG, "Backend binder did not arrive after launch command")
            notify("辅助服务尚未连上", "请回到应用重试，或查看电脑激活方式。" +
                commandFailure?.let { "\n设置命令返回：$it" }.orEmpty())
        }
    }

    private fun notification(title: String, message: String, input: Boolean = false, portInput: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, if (input || portInput) PAIR_CHANNEL else CHANNEL)
            .setGroup(NotificationGroups.WIRELESS)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) return
        if (pairing && pairPort > 0) {
            // A failed reconnect must not replace an open pairing-code reply action
            // with a connection-port field while the user is entering the code.
            notify("输入无线调试配对码", "输入当前系统窗口中的6位配对码，完成后自动激活。", input = true)
            return
        }
        getSystemService(NotificationManager::class.java).notify(ID,
            notification("已配对，请确认连接端口", "保持无线调试首页打开。如果未自动连接，请输入IP地址冒号后面的端口号。",
                portInput = true))
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
    }

    private fun notify(title: String, message: String, input: Boolean = false) {
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) return
        getSystemService(NotificationManager::class.java).notify(ID, notification(title, message, input))
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
    }

    private fun cancelPairNotification() {
        getSystemService(NotificationManager::class.java).cancel(LEGACY_PAIR_ID)
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
    }

    private fun finish(title: String, message: String) {
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) {
            cancelForDisabledMode(this)
            stopSelf()
            return
        }
        cancelPairNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val result = NotificationCompat.Builder(this, CHANNEL)
            .setGroup(NotificationGroups.WIRELESS)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSmallIcon(R.drawable.ic_qs_tile_adaptive_hz)
            .setContentTitle(title).setContentText(message).setAutoCancel(true)
            .setColor(android.graphics.Color.rgb(0, 122, 255)).build()
        getSystemService(NotificationManager::class.java).notify(ID + 1, result)
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        cancelPairNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationGroups.refresh(this, NotificationGroups.WIRELESS)
        endpointListener = null
        main.removeCallbacksAndMessages(null)
        pairDiscovery?.stop()
        connectDiscovery?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val TAG = "WirelessActivation"
        private const val MAX_SHELL_OUTPUT = 2048
        private const val MAX_PERMISSION_OUTPUT = 1024
        private const val MAX_PERMISSION_TAIL = 512
        private const val OUTPUT_TAIL_MARKER = "\nROYTOOLS_OUTPUT_TAIL\n"
        private val SHELL_FAILURE = Regex("exception|permission (?:denied|denial)|unknown permission|error:|not found|failed", RegexOption.IGNORE_CASE)
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
        fun cancelForDisabledMode(context: android.content.Context) {
            context.stopService(android.content.Intent(context,WirelessSetupService::class.java))
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            manager.cancel(ID);manager.cancel(ID+1);manager.cancel(LEGACY_PAIR_ID)
            com.github.kr328.clash.common.util.NotificationGroups.refresh(context,com.github.kr328.clash.common.util.NotificationGroups.WIRELESS)
        }
        private const val CHANNEL = "wireless_activation_progress"
        private const val PAIR_CHANNEL = "wireless_pairing_heads_up"
        private const val LEGACY_PAIR_ID = 2003
        private const val ID = 2001
        const val ACTION_PORT = "wireless_connect_port"
        const val ACTION_CODE = "wireless_pair_code"
        const val ACTION_CONNECT = "wireless_connect"
    }
}

/** Only explicit TLS certificate alerts invalidate pairing, never ordinary IO failures. */
internal fun isRejectedWirelessAdbCredential(error: Throwable): Boolean {
    val alerts = arrayOf("certificate_unknown", "unknown_ca", "bad_certificate", "certificate_required",
        "certificate_expired", "certificate_revoked", "unsupported_certificate")
    var cause: Throwable? = error
    repeat(16) {
        val current = cause ?: return false
        if (current is SSLException && alerts.any { it in current.message.orEmpty().lowercase(Locale.ROOT) }) return true
        cause = current.cause
    }
    return false
}









