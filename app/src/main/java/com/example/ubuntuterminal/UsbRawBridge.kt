package com.example.ubuntuterminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw USB bridge for ARBITRARY devices (anything the kernel does not already
 * claim): exposes control, bulk and interrupt transfers over a simple
 * line-based TCP protocol on 127.0.0.1:8040, so code inside the Ubuntu
 * container (bash /dev/tcp, python sockets, node, ...) can drive any USB
 * hardware directly - "execute code against the USB device, whatever it is".
 *
 * Protocol (one command per line, arguments in hex, replies end with \n):
 *   C <bmReqType> <bRequest> <wValue> <wIndex> <wLength> [dataHex]
 *        control transfer -> "D <hex>" (IN) or "OK <n>" (OUT) or "ERR ..."
 *   W <endpoint> <dataHex>
 *        bulk/interrupt OUT write -> "OK <n>"
 *   R <endpoint> <maxLen> [timeoutMs]
 *        bulk/interrupt IN read -> "D <hex>" or "T" on timeout
 *   I  list endpoints          -> "EP <addr> <dir> <type> <maxPacket>"
 *   X  close this connection
 *
 * Terminal side:
 *   dapi usbraw list | open VID:PID | status | close
 */
object UsbRawBridge {

    private const val RAW_PORT = 8040
    private const val ACTION_PERM = "com.example.ubuntuterminal.USB_PERMISSION"

    private val lock = Any()
    private var connection: UsbDeviceConnection? = null
    private var rawDevice: UsbDevice? = null
    private var openVidPid = ""
    private var rawServer: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val endpoints = HashMap<Int, UsbEndpoint>()   // address -> endpoint
    private var receiversRegistered = false
    private var pendingVidPid: String? = null

    private val notOpen = "ERR raw device not open (first: dapi usbraw open vid:pid)\n"

    // ---------------------------------------------------------------- commands

    fun list(context: Context): String {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values
        if (devices.isEmpty()) return "no USB devices\n"
        return devices.joinToString("") { d ->
            buildString {
                append("vid:pid=%04x:%04x  %s  permission=%b  interfaces=%d\n".format(
                    d.vendorId, d.productId, d.productName ?: "?", usb.hasPermission(d), d.interfaceCount))
                repeat(d.interfaceCount) { i ->
                    val iface = d.getInterface(i)
                    append("  if#%d class=0x%02x subclass=0x%02x endpoints=%d\n".format(
                        i, iface.interfaceClass, iface.interfaceSubclass, iface.endpointCount))
                }
            }
        }
    }

    fun open(context: Context, vidPid: String): String {
        if (vidPid.isBlank()) return "ERR usage: usbraw open vid:pid\n"
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        registerReceivers(app)
        val device = usb.deviceList.values.firstOrNull {
            "%04x:%04x".format(it.vendorId, it.productId) == vidPid.lowercase()
        } ?: return "ERR device $vidPid not found (run: dapi usbraw list)\n"

        if (!usb.hasPermission(device)) {
            pendingVidPid = vidPid.lowercase()
            val pi = PendingIntent.getBroadcast(
                app, 4,
                Intent(ACTION_PERM).setPackage(app.packageName).putExtra("from", "usbraw"),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            usb.requestPermission(device, pi)
            return "permission dialog shown - the device opens automatically after allowing\ncheck: dapi usbraw status\n"
        }

        close()
        return try {
            val conn = usb.openDevice(device)
                ?: return "ERR cannot open the device (kernel driver busy?)\n"
            val claimed = StringBuilder()
            val eps = HashMap<Int, UsbEndpoint>()
            repeat(device.interfaceCount) { i ->
                val iface = device.getInterface(i)
                val ok = runCatching { conn.claimInterface(iface, true) }.getOrDefault(false)
                claimed.append(if (ok) "+" else "-").append(i)
                repeat(iface.endpointCount) { j ->
                    val ep = iface.getEndpoint(j)
                    eps[ep.address] = ep
                }
            }
            synchronized(lock) {
                connection = conn
                rawDevice = device
                openVidPid = vidPid.lowercase()
                endpoints.clear()
                endpoints.putAll(eps)
            }
            startServer()
            "OK $vidPid open, interfaces claimed [$claimed], ${eps.size} endpoints\n" +
                "raw protocol on 127.0.0.1:$RAW_PORT  (C/W/R/I/X - see dapi help)\n"
        } catch (e: Exception) {
            "ERR ${e.message ?: e.javaClass.simpleName}\n"
        }
    }

    fun status(): String = synchronized(lock) {
        if (connection == null) "closed\n"
        else buildString {
            append("open ").append(openVidPid).append('\n')
            append("endpoints:\n")
            endpoints.values.sortedBy { it.address }.forEach {
                append("  0x%02x %s %s maxPacket=%d\n".format(
                    it.address,
                    if (it.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT",
                    when (it.type) {
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        else -> "?"
                    },
                    it.maxPacketSize))
            }
        }
    }

    fun close(): String {
        running.set(false)
        synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
            rawDevice = null
            endpoints.clear()
        }
        runCatching { rawServer?.close() }
        rawServer = null
        return "OK closed\n"
    }

    // -------------------------------------------------------------- TCP protocol

    private fun startServer() {
        synchronized(lock) {
            if (running.get()) return
            running.set(true)
            rawServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", RAW_PORT))
            }
        }
        Thread {
            while (running.get()) {
                val client = try {
                    rawServer?.accept()
                } catch (_: Exception) {
                    break
                } ?: break
                Thread { serve(client) }
                    .apply { name = "usbraw-client"; isDaemon = true; start() }
            }
        }.apply { name = "usbraw-accept"; isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 60_000
                val input = c.getInputStream()
                val out = c.getOutputStream()
                while (true) {
                    val line = ByteArrayOutputStream()
                    var b = input.read()
                    while (b != -1 && b != '\n'.code && b != '\r'.code) {
                        line.write(b)
                        b = input.read()
                    }
                    if (b == -1) break
                    if (b == '\r'.code) input.read()
                    val cmd = line.toString(Charsets.UTF_8.name()).trim().split(Regex("\\s+"))
                    if (cmd[0].isEmpty()) continue
                    val reply = execute(cmd)
                    out.write(reply.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    out.flush()
                    if (cmd[0] == "X") break
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun execute(cmd: List<String>): String = try {
        when (cmd[0].uppercase()) {
            "I" -> synchronized(lock) {
                if (connection == null) notOpen.trim()
                else endpoints.values.joinToString("\n") {
                    "EP 0x%02x %s %s maxPacket=%d".format(
                        it.address,
                        if (it.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT",
                        when (it.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                            else -> "control"
                        },
                        it.maxPacketSize)
                }
            }
            "C" -> controlTransfer(cmd)
            "W" -> writeTransfer(cmd)
            "R" -> readTransfer(cmd)
            "X" -> "BYE"
            else -> "ERR unknown command ${cmd[0]} (C/W/R/I/X)"
        }
    } catch (e: Exception) {
        "ERR ${e.message ?: e.javaClass.simpleName}"
    }

    /** C <bmReqType> <bRequest> <wValue> <wIndex> <wLength> [dataHex] */
    private fun controlTransfer(cmd: List<String>): String {
        val conn = synchronized(lock) { connection } ?: return notOpen.trim()
        if (cmd.size < 6) return "ERR usage: C bmReqType bRequest wValue wIndex wLength [dataHex]"
        val reqType = cmd[1].toInt(16)
        val request = cmd[2].toInt(16)
        val value = cmd[3].toInt(16)
        val index = cmd[4].toInt(16)
        val length = cmd[5].toInt()
        val isOut = (reqType and 0x80) == 0
        if (isOut) {
            val data = if (cmd.size > 6) hexToBytes(cmd[6]) else ByteArray(0)
            val n = conn.controlTransfer(reqType, request, value, index, data, data.size, 3000)
            return if (n >= 0) "OK $n" else "ERR control transfer failed ($n)"
        }
        val buf = ByteArray(maxOf(length, 0))
        val n = conn.controlTransfer(reqType, request, value, index, buf, buf.size, 3000)
        return if (n >= 0) "D " + bytesToHex(buf, n) else "ERR control transfer failed ($n)"
    }

    /** W <endpoint> <dataHex> */
    private fun writeTransfer(cmd: List<String>): String {
        val conn = synchronized(lock) { connection } ?: return notOpen.trim()
        if (cmd.size < 3) return "ERR usage: W endpoint dataHex"
        val ep = synchronized(lock) { endpoints[cmd[1].toInt(16)] }
            ?: return "ERR unknown endpoint 0x${cmd[1]}"
        val data = hexToBytes(cmd[2])
        val n = conn.bulkTransfer(ep, data, data.size, 3000)
        return if (n >= 0) "OK $n" else "ERR write failed ($n)"
    }

    /** R <endpoint> <maxLen> [timeoutMs] */
    private fun readTransfer(cmd: List<String>): String {
        val conn = synchronized(lock) { connection } ?: return notOpen.trim()
        if (cmd.size < 3) return "ERR usage: R endpoint maxLen [timeoutMs]"
        val ep = synchronized(lock) { endpoints[cmd[1].toInt(16)] }
            ?: return "ERR unknown endpoint 0x${cmd[1]}"
        val maxLen = cmd[2].toInt()
        val timeout = if (cmd.size > 3) cmd[3].toInt() else 1000
        val buf = ByteArray(maxOf(maxLen, 1))
        val n = conn.bulkTransfer(ep, buf, buf.size, timeout)
        return when {
            n > 0 -> "D " + bytesToHex(buf, n)
            n == 0 -> "D "
            else -> "T"
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun hexToBytes(s: String): ByteArray {
        val clean = if (s.length % 2 == 1) "0$s" else s
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    private fun bytesToHex(b: ByteArray, n: Int): String {
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            sb.append("0123456789abcdef"[(b[i].toInt() shr 4) and 0xF])
            sb.append("0123456789abcdef"[b[i].toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun registerReceivers(app: Context) {
        if (receiversRegistered) return
        receiversRegistered = true

        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra("from") != "usbraw") return
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
                val vid = pendingVidPid ?: return
                open(c, vid)
            }
        }, IntentFilter(ACTION_PERM))

        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                if ("%04x:%04x".format(dev.vendorId, dev.productId) == openVidPid) close()
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }
}
