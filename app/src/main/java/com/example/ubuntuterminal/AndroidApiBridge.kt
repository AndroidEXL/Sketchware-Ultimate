package com.example.ubuntuterminal

import pro.sketchware.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Bridge that lets programs inside the Ubuntu container call Android APIs.
 *
 * Listens on 127.0.0.1:8037 and speaks a dead-simple tab-separated protocol,
 * one request per connection, so the guest-side `dapi` tool needs nothing but
 * bash's /dev/tcp:
 *
 *   vibrate\t<millis>            → vibrate the device
 *   notify\t<title>\t<text>      → post a system notification
 *   notify-done\t<durMs>\t<rc>\t<cmd> → command-finished alert (notification + Telegram)
 *   toast\t<text>                → show a toast
 *   battery                      → battery status
 *   usb                          → list USB devices
 *   usbp\t<vid:pid>              → request permission for a USB device
 *   torch\t<on|off>              → camera flashlight
 *   info                         → device info
 *   ping                         → bridge check (version/uptime)
 *   clip [text]                   → get/set the Android clipboard
 *   usbser list|open|send|read|…   → USB serial bridge (see UsbSerialBridge)
 *   flash list|open|ls|get|put|…    → USB flash drive bridge (see UsbFlashBridge)
 *   usbraw open                     → raw USB bridge for code (see UsbRawBridge)
 */
object AndroidApiBridge {

    /** Default Telegram bot for command-finished alerts (personal build; editable in settings). */
    const val DEFAULT_TG_TOKEN = "8686938162:AAGqvhC14Y039IbU7NClF6bdHecGV95CW9U"
    const val DEFAULT_TG_CHAT = "8627617658"

    private const val PORT = 8037
    private const val CHANNEL_ID = "dapi"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var wantRun = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedAt = System.currentTimeMillis()

    /**
     * Starts the bridge if it is not already running. Safe to call any number
     * of times (Application, activity and service all call it). The server
     * thread retries the bind forever with a short backoff, so a temporarily
     * busy port - or an OEM killing one of our components - can never take
     * the bridge down for good. It only dies with the process itself.
     */
    fun start(context: Context) {
        if (wantRun) return
        wantRun = true
        val app = context.applicationContext
        Thread {
            while (wantRun) {
                try {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress("127.0.0.1", PORT))
                        server = ss
                        while (wantRun) {
                            val client = try {
                                ss.accept()
                            } catch (_: Exception) {
                                break // socket closed by stop()
                            }
                            try {
                                handle(client, app)
                            } catch (_: Exception) {
                                // one bad request must never kill the server
                            }
                        }
                        server = null
                    }
                } catch (_: Exception) {
                    // bind failed (e.g. port busy) - retry after a pause
                }
                if (wantRun) {
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }.apply {
            name = "dapi-bridge"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        wantRun = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket, app: Context) {
        try {
            client.use { c ->
                // Read the request as raw bytes until a line end, EOF - or a
                // short timeout, so even clients that do not terminate their
                // request with a newline (as the very first dapi builds did)
                // still get served instead of dead-locking the handshake.
                c.soTimeout = 1500
                val input = c.getInputStream()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(512)
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        -2 // no newline arrived - fall back to what we have
                    }
                    if (n == -2 || n < 0) break
                    out.write(buf, 0, n)
                    val s = out.toString(Charsets.UTF_8.name())
                    if (s.contains('\n') || s.contains('\r')) break
                }
                val req = out.toString(Charsets.UTF_8.name()).trim()
                if (req.isEmpty()) return
                val parts = req.split('\t')
                // "flash cat" streams raw (binary-safe) bytes straight through
                val response: ByteArray =
                    if (parts.firstOrNull() == "flash" && parts.getOrNull(1) == "cat")
                        UsbFlashBridge.cat(parts.getOrNull(2) ?: "")
                    else runCatching { execute(app, parts).toByteArray(Charsets.UTF_8) }
                        .getOrElse {
                            ("ERR " + (it.message ?: it.javaClass.simpleName) + "\n").toByteArray()
                        }
                c.getOutputStream().apply {
                    write(response)
                    flush()
                }
            }
        } catch (_: Exception) {
            // one bad request must never kill the server
        }
    }

    private fun execute(app: Context, parts: List<String>): String = when (parts.firstOrNull()) {
        "vibrate" -> {
            vibrate(app, parts.getOrNull(1)?.toLongOrNull() ?: 300L)
            "OK\n"
        }
        "notify" -> {
            notify(app, parts.getOrElse(1) { "Ubuntu Terminal" }, parts.getOrElse(2) { "" })
            "OK\n"
        }
        "notify-done" -> {
            notifyDone(
                app,
                parts.getOrNull(1)?.toLongOrNull() ?: -1L,
                parts.getOrNull(2)?.toIntOrNull() ?: -1,
                parts.getOrElse(3) { "" }
            )
            "OK\n"
        }
        "toast" -> {
            val msg = parts.drop(1).joinToString(" ").ifBlank { " " }
            mainHandler.post { ToastCompat.show(app, msg) }
            "OK\n"
        }
        "battery" -> battery(app)
        "usb" -> usbList(app)
        "usbp" -> usbPermission(app, parts.getOrNull(1) ?: "")
        "usbser" -> usbSerial(app, parts.drop(1))
        "flash" -> flash(app, parts.drop(1))
        "usbraw" -> rawUsb(app, parts.drop(1))
        "vnc" -> vnc(app, parts.drop(1))
        "keepalive" -> keepalive(app, parts.drop(1))
        "torch" -> {
            torch(app, (parts.getOrNull(1) ?: "on") == "on")
            "OK\n"
        }
        "info" -> info(app)
        "clip" -> clip(app, parts.drop(1).joinToString(" ").trim())
        "ping" -> "pong v" + versionName(app) +
            " uptime=" + ((System.currentTimeMillis() - startedAt) / 1000) + "s\n"
        else -> "ERR unknown command\n"
    }

    // ---------------------------------------------------------------- commands

    /** dapi clip - read the Android clipboard, or set it when text is given. */
    private fun clip(app: Context, text: String): String {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (text.isEmpty()) {
            val clip = cm.primaryClip ?: return "(clipboard is empty)\n"
            val s = clip.getItemAt(0)?.coerceToText(app)?.toString() ?: ""
            return if (s.isEmpty()) "(clipboard is empty)\n" else s + "\n"
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dapi", text))
        return "OK\n"
    }

    /** dapi flash ... - USB flash drive bridge, see [UsbFlashBridge]. */
    private fun flash(app: Context, args: List<String>): String = when (args.firstOrNull()) {
        null -> "usage: flash list | open vid:pid | info | ls [path] | cat FILE | get FILE [LOCAL] | " +
            "put LOCAL REMOTE | mkdir PATH | rm PATH | format [TYPE] CONFIRM [LABEL] | eject\n" +
            "format TYPE: fat32 (default) | fat16 | exfat | ntfs | ext2 | ext3 | ext4\n"
        "list" -> UsbFlashBridge.list(app)
        "open" -> UsbFlashBridge.open(app, args.getOrNull(1) ?: "")
        "info" -> UsbFlashBridge.info()
        "ls" -> UsbFlashBridge.ls(args.drop(1).joinToString(" "))
        "cat" -> "(binary path - intercepted in handle())\n"
        "mkdir" -> UsbFlashBridge.mkdir(args.drop(1).joinToString(" "))
        "rm" -> UsbFlashBridge.rm(args.drop(1).joinToString(" "))
        "format" -> {
            val a = args.drop(1)
            val types = setOf("fat32", "fat16", "exfat", "ntfs", "ext2", "ext3", "ext4")
            when {
                a.firstOrNull()?.uppercase() == "CONFIRM" ->
                    UsbFlashBridge.format(app, "CONFIRM", a.drop(1).joinToString(" "))
                a.firstOrNull()?.lowercase() in types ->
                    UsbFlashBridge.formatMulti(app, a[0].lowercase(), a.getOrNull(1) ?: "", a.drop(2).joinToString(" "))
                else ->
                    "ERR usage: flash format [TYPE] CONFIRM [LABEL]\n" +
                        "TYPE: fat32 (default) | fat16 | exfat | ntfs | ext2 | ext3 | ext4\n"
            }
        }
        "eject" -> UsbFlashBridge.eject()
        "get", "put" -> "ERR get/put are streamed by the dapi script over TCP 8041\n"
        else -> "ERR unknown flash subcommand '" + args.first() + "'\n"
    }

    /** dapi usbraw ... - raw USB bridge, see [UsbRawBridge]. */
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Keeps the CPU awake with the screen off, so long-running things inside
     * Ubuntu (the Telegram bot, tmate, VNC) survive backgrounding.
     */
    private fun keepalive(context: Context, args: List<String>): String {
        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (args.firstOrNull()) {
            "on" -> {
                if (wakeLock?.isHeld == true) {
                    "OK keepalive already on\n"
                } else {
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "UbuntuTerminal:keepalive"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    "OK keepalive ON - CPU stays awake with the screen off\n" +
                        "(the bot / tmate / VNC keep running - uses some battery!)\n" +
                        "turn off with: dapi keepalive off\n"
                }
            }
            "off" -> {
                runCatching { wakeLock?.release() }
                wakeLock = null
                "OK keepalive off\n"
            }
            "status" ->
                "keepalive: " + (if (wakeLock?.isHeld == true) "ON" else "off") + "\n"
            else -> "ERR usage: keepalive on|off|status\n"
        }
    }

    private fun vnc(context: Context, args: List<String>): String {
        return when (args.firstOrNull()) {
            "open" -> {
                context.applicationContext.startActivity(
                    Intent(context.applicationContext, VncActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "OK VNC view opening\n"
            }
            else -> "ERR usage: vnc open\n"
        }
    }

    private fun rawUsb(app: Context, args: List<String>): String = when (args.firstOrNull()) {
        null -> "usage: usbraw list | open vid:pid | status | close\n" +
            "after open: line protocol C/W/R/I/X on 127.0.0.1:8040 (see dapi help)\n"
        "list" -> UsbRawBridge.list(app)
        "open" -> UsbRawBridge.open(app, args.getOrNull(1) ?: "")
        "status" -> UsbRawBridge.status()
        "close" -> UsbRawBridge.close()
        else -> "ERR unknown usbraw subcommand '" + args.first() + "'\n"
    }

    /** dapi usbser ... - USB serial adapter bridge, see [UsbSerialBridge]. */
    private fun usbSerial(app: Context, args: List<String>): String = when (args.firstOrNull()) {
        null -> "usage: usbser list | open vid:pid [baud] | send TEXT | sendraw TEXT | read | status | close\n"
        "list" -> UsbSerialBridge.list(app)
        "open" -> UsbSerialBridge.open(app, args.getOrNull(1) ?: "", args.getOrNull(2)?.toIntOrNull() ?: 115200)
        "send" -> UsbSerialBridge.send(args.drop(1).joinToString(" "), true)
        "sendraw" -> UsbSerialBridge.send(args.drop(1).joinToString(" "), false)
        "read" -> UsbSerialBridge.read()
        "status" -> UsbSerialBridge.status()
        "close" -> UsbSerialBridge.close()
        "dtr" -> UsbSerialBridge.setDtr(args.getOrNull(1) != "off")
        "rts" -> UsbSerialBridge.setRts(args.getOrNull(1) != "off")
        else -> "ERR unknown usbser subcommand '" + args.first() + "'\n"
    }

    @Suppress("DEPRECATION")
    private fun versionName(app: Context): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
            .getOrNull() ?: "?"

    private fun vibrate(app: Context, millis: Long) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(millis)
        }
    }

    private fun notify(app: Context, title: String, text: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, app.getString(R.string.ut_dapi_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val pi = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ut_ic_terminal_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n)
    }

    /**
     * dapi notify-done DURms RC CMD — a long command finished: local
     * notification (channel above) + Telegram message, both respecting the
     * settings screen switches. Telegram runs off the main thread.
     */
    private fun notifyDone(app: Context, durMs: Long, rc: Int, cmd: String) {
        val sp = app.getSharedPreferences("ut_settings", Context.MODE_PRIVATE)
        if (sp.getString("cmd_notify", "1") != "1") return
        if (durMs < 0) return
        val secs = durMs / 1000
        val durTxt = when {
            secs >= 3600 -> "${secs / 3600}س ${(secs % 3600) / 60}د"
            secs >= 60 -> "${secs / 60}د ${secs % 60}ث"
            else -> "${secs}ث"
        }
        val title = (if (rc == 0) "✅ خلص الأمر" else "❌ فشل الأمر (كود $rc)") + " — $durTxt"
        val text = cmd.ifBlank { "(أمر غير معروف)" }
        notifyUser(app, title, text)
    }

    /**
     * v1.30: package-install notifications ("⏳ تثبيت <pkg>" when a format
     * auto-installs its mkfs tool). OFF by default - the user asked for
     * them to be extinguishable; enable from settings.
     */
    fun pkgNotifyEnabled(app: Context): Boolean =
        app.getSharedPreferences("ut_settings", Context.MODE_PRIVATE)
            .getString("pkg_notify", "0") == "1"

    /** Local notification + Telegram mirror; used by long-running operations. */
    fun notifyUser(app: Context, title: String, text: String) {
        runCatching { notify(app, title, text) }
        thread(name = "ut-tg-notify") { sendTelegram(app, title + "\n" + text) }
    }

    /** Sends a Telegram message with the bot configured in settings (best-effort). */
    private fun sendTelegram(app: Context, text: String) {
        runCatching {
            val sp = app.getSharedPreferences("ut_settings", Context.MODE_PRIVATE)
            val token = sp.getString("tg_token", DEFAULT_TG_TOKEN) ?: ""
            val chat = sp.getString("tg_chat", DEFAULT_TG_CHAT) ?: ""
            if (token.isEmpty() || chat.isEmpty()) return
            val conn = java.net.URL("https://api.telegram.org/bot$token/sendMessage")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val form = "chat_id=" + java.net.URLEncoder.encode(chat, "UTF-8") +
                "&text=" + java.net.URLEncoder.encode(text, "UTF-8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        }
    }

    private fun battery(app: Context): String {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "ERR no battery data\n"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return buildString {
            append("level: ").append(if (level >= 0 && scale > 0) level * 100 / scale else -1).append('%').append('\n')
            append("status: ").append(status).append('\n')
            append("temperature: ")
                .append(if (temp != Int.MIN_VALUE) temp / 10.0 else -1.0).append(" °C\n")
        }
    }

    private fun usbList(app: Context): String {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList
        if (devices.isEmpty()) return "no USB devices\n"
        return devices.entries.joinToString("") { (_, d) ->
            "vid=0x%04x pid=0x%04x name=%s permission=%b\n".format(
                d.vendorId, d.productId, d.productName ?: "?", usb.hasPermission(d)
            )
        }
    }

    private fun usbPermission(app: Context, vidPid: String): String {
        if (vidPid.isBlank()) return "ERR usage: usbp vid:pid (e.g. 046d:c31c)\n"
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull {
            "%04x:%04x".format(it.vendorId, it.productId) == vidPid.lowercase()
        } ?: return "ERR device $vidPid not found\n"
        if (usb.hasPermission(dev)) return "already granted\n"
        val pi = PendingIntent.getBroadcast(
            app, 1,
            Intent("com.example.ubuntuterminal.USB_PERMISSION").setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        usb.requestPermission(dev, pi)
        return "permission dialog shown – run 'dapi usb' to verify\n"
    }

    private fun torch(app: Context, on: Boolean) {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        cm.setTorchMode(id, on)
    }

    private fun info(app: Context): String = buildString {
        append("model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("abi: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
    }

    /** Toast from a background thread. */
    private object ToastCompat {
        fun show(app: Context, msg: String) {
            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
