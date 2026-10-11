package com.example.ubuntuterminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB serial bridge: opens a USB serial adapter (CH340, CP210x, FTDI,
 * Prolific PL2303, CDC/ACM - handled by the vendored usb-serial-for-android
 * library) and relays its traffic to/from TCP 127.0.0.1:8039, so ANY program
 * inside the Ubuntu container can talk to the attached device - Arduino,
 * ESP32, a Raspberry Pi console, a router, ... - using plain /dev/tcp.
 *
 * Terminal-side commands (see the `dapi` script):
 *
 *   dapi usbser list                    list serial-capable USB devices
 *   dapi usbser open VID:PID [baud]     open the port + start the TCP relay
 *   dapi usbser send TEXT               write TEXT + CRLF to the port
 *   dapi usbser sendraw TEXT            write TEXT exactly as received
 *   dapi usbser read                    print (and clear) buffered data
 *   dapi usbser status                  state, baud and byte counters
 *   dapi usbser close                   close the port and the relay
 *   dapi usbsh                          interactive serial console
 */
object UsbSerialBridge {

    private const val RELAY_PORT = 8039
    private const val ACTION_PERM = "com.example.ubuntuterminal.USB_PERMISSION"
    /** Bytes kept for `dapi usbser read` (older data is dropped). */
    private const val PENDING_LIMIT = 64 * 1024

    private val lock = Any()
    private val running = AtomicBoolean(false)

    private var port: UsbSerialPort? = null
    private var openVidPid = ""
    private var openBaud = 0
    private var totalIn = 0L
    private var totalOut = 0L
    private val pending = ByteArrayOutputStream()

    private var relayServer: ServerSocket? = null
    private val clients = ConcurrentLinkedQueue<Socket>()

    private var receiversRegistered = false
    private var pendingVidPid: String? = null
    private var pendingBaud = 115200

    // ------------------------------------------------------------- commands

    fun list(context: Context): String {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        if (drivers.isEmpty())
            return "no serial-capable USB devices found (run: dapi usb)\n"
        return drivers.joinToString("") { d ->
            "vid:pid=%04x:%04x  driver=%s  ports=%d  permission=%b\n".format(
                d.device.vendorId, d.device.productId,
                d.javaClass.simpleName.replace("SerialDriver", ""),
                d.ports.size, usb.hasPermission(d.device)
            )
        }
    }

    fun open(context: Context, vidPid: String, baud: Int): String {
        if (vidPid.isBlank()) return "ERR usage: usbser open vid:pid [baud]\n"
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        registerReceivers(app)

        val device = usb.deviceList.values.firstOrNull {
            "%04x:%04x".format(it.vendorId, it.productId) == vidPid.lowercase()
        } ?: return "ERR device $vidPid not found (run: dapi usbser list)\n"

        if (!usb.hasPermission(device)) {
            pendingVidPid = vidPid.lowercase()
            pendingBaud = baud
            val pi = PendingIntent.getBroadcast(
                app, 2,
                Intent(ACTION_PERM).setPackage(app.packageName).putExtra("from", "usbser"),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            usb.requestPermission(device, pi)
            return "permission dialog shown - allow it and the port opens automatically\n" +
                "check with: dapi usbser status\n"
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: return "ERR no serial driver for $vidPid\n"
        val p = driver.ports.firstOrNull()
            ?: return "ERR device has no serial ports\n"
        val connection = usb.openDevice(device)
            ?: return "ERR cannot open the device (try reconnecting it)\n"

        close()
        return try {
            p.open(connection)
            p.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            openVidPid = vidPid.lowercase()
            openBaud = baud
            totalIn = 0
            totalOut = 0
            synchronized(lock) { pending.reset() }
            running.set(true)
            startRelay()
            Thread { readLoop(p) }.apply { name = "usbser-read"; isDaemon = true; start() }
            "OK $vidPid open at $baud 8N1 - relay listening on 127.0.0.1:$RELAY_PORT\n" +
                "interactive console: dapi usbsh\n"
        } catch (e: Exception) {
            runCatching { p.close() }
            "ERR ${e.message ?: e.javaClass.simpleName}\n"
        }
    }

    fun send(text: String, addNewline: Boolean): String {
        val p = port ?: return "ERR not open (first: dapi usbser open vid:pid [baud])\n"
        val data = (text + if (addNewline) "\r\n" else "").toByteArray(Charsets.UTF_8)
        return try {
            p.write(data, 500)
            synchronized(lock) { totalOut += data.size }
            "OK sent ${data.size} bytes\n"
        } catch (e: Exception) {
            "ERR ${e.message ?: e.javaClass.simpleName}\n"
        }
    }

    fun read(): String {
        val data = synchronized(lock) {
            if (pending.size() == 0) return "(no data)\n"
            val d = pending.toByteArray()
            pending.reset()
            d
        } ?: return "(no data)\n"
        return String(data, Charsets.UTF_8)
    }

    fun status(): String = synchronized(lock) {
        if (port == null) "closed\n"
        else buildString {
            append("open ").append(openVidPid).append(" at ").append(openBaud).append(" 8N1\n")
            append("relay: 127.0.0.1:").append(RELAY_PORT)
                .append("  clients=").append(clients.size).append('\n')
            append("received: ").append(totalIn).append(" bytes, sent: ").append(totalOut).append(" bytes\n")
        }
    }

    /** Control lines - used e.g. for the Arduino auto-reset trick. */
    fun setDtr(on: Boolean): String {
        val p = port ?: return "ERR not open\n"
        return try { p.setDTR(on); "OK dtr=$on\n" } catch (e: Exception) { "ERR ${e.message}\n" }
    }

    fun setRts(on: Boolean): String {
        val p = port ?: return "ERR not open\n"
        return try { p.setRTS(on); "OK rts=$on\n" } catch (e: Exception) { "ERR ${e.message}\n" }
    }

    fun close(): String {
        running.set(false)
        runCatching { port?.close() }
        port = null
        runCatching { relayServer?.close() }
        relayServer = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        return "OK closed\n"
    }

    // -------------------------------------------------------------- internals

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(1024)
        while (running.get()) {
            val n = try {
                p.read(buf, 100)
            } catch (_: Exception) {
                break // device detached or closed
            }
            if (n > 0) {
                val chunk = buf.copyOf(n)
                synchronized(lock) {
                    totalIn += n
                    pending.write(chunk, 0, chunk.size)
                    if (pending.size() > PENDING_LIMIT * 2) {
                        val all = pending.toByteArray()
                        pending.reset()
                        pending.write(all, all.size - PENDING_LIMIT, PENDING_LIMIT)
                    }
                }
                clients.forEach { c ->
                    runCatching {
                        c.getOutputStream().apply { write(chunk); flush() }
                    }
                }
            }
        }
        // unexpected exit while still "open" = device unplugged
        if (running.getAndSet(false)) {
            port = null
        }
    }

    private fun startRelay() {
        relayServer = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", RELAY_PORT))
        }
        Thread {
            while (running.get()) {
                val client = try {
                    relayServer?.accept()
                } catch (_: Exception) {
                    break
                } ?: break
                clients.add(client)
                Thread { pumpClient(client) }
                    .apply { name = "usbser-client"; isDaemon = true; start() }
            }
        }.apply { name = "usbser-relay"; isDaemon = true; start() }
    }

    /** TCP client -> serial direction. */
    private fun pumpClient(client: Socket) {
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = client.getInputStream().read(buf)
                if (n < 0) break
                val p = port ?: break
                p.write(buf.copyOf(n), 500)
                synchronized(lock) { totalOut += n }
            }
        } catch (_: Exception) {
        }
        clients.remove(client)
        runCatching { client.close() }
    }

    private fun registerReceivers(app: Context) {
        if (receiversRegistered) return
        receiversRegistered = true

        // auto-open as soon as the user grants USB permission
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra("from") != "usbser") return
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
                val vid = pendingVidPid ?: return
                open(c, vid, pendingBaud)
            }
        }, IntentFilter(ACTION_PERM))

        // close cleanly when the adapter is unplugged
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    ?: return
                if ("%04x:%04x".format(dev.vendorId, dev.productId) == openVidPid) close()
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }
}
