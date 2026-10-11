package com.example.ubuntuterminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.UsbFile
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB flash drive bridge, built on the vendored libaums library (Apache 2.0).
 * Gives the Ubuntu container full access to FAT32 USB sticks - list, read,
 * write, delete, create, format - with NO root and no mounting.
 *
 * Terminal-side commands (see the `dapi` script):
 *
 *   dapi flash list                    list mass storage devices
 *   dapi flash open VID:PID            request permission + attach the stick
 *   dapi flash info                    filesystem, capacity, free space
 *   dapi flash ls [path]               list a directory
 *   dapi flash cat FILE                print a small file
 *   dapi flash get FILE [LOCAL]        download (streamed over TCP 8041)
 *   dapi flash put LOCAL REMOTE        upload (streamed over TCP 8041)
 *   dapi flash mkdir PATH              create a directory
 *   dapi flash rm PATH                 delete a file or (empty) directory
 *   dapi flash format CONFIRM [LABEL]  quick-format the stick as FAT32 (!!)
 *   dapi flash eject                   release the stick
 */
object UsbFlashBridge {

    private const val XFER_PORT = 8041
    private const val ACTION_PERM = "com.example.ubuntuterminal.USB_PERMISSION"
    /** Safety cap for `flash cat` through the 8037 bridge (use `get` for big files). */
    private const val CAT_LIMIT = 8L * 1024 * 1024

    private val lock = Any()
    @Volatile private var imageFormatBusy = false
    private var device: UsbMassStorageDevice? = null
    private var rootFile: UsbFile? = null
    private var openVidPid = ""
    private var transferServer: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var receiversRegistered = false
    private var pendingVidPid: String? = null

    private val notOpen = "ERR flash not open (first: dapi flash open vid:pid)\n"
    private val noFs = "ERR no filesystem on stick (NTFS/exFAT/unformatted) - quick-format first:\n" +
        "dapi flash format CONFIRM [LABEL]\n" +
        "or another filesystem: dapi flash format TYPE CONFIRM [LABEL]\n" +
        "TYPE: fat16|exfat|ntfs|ext2|ext3|ext4\n"
    private fun noRootMsg(): String = if (device == null) notOpen else noFs

    // ---------------------------------------------------------------- commands

    fun list(context: Context): String {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devs = UsbMassStorageDevice.getMassStorageDevices(context)
        if (devs.isEmpty()) return "no mass storage devices found (run: dapi usb)\n"
        return devs.joinToString("") { d ->
            "vid:pid=%04x:%04x  %s  permission=%b\n".format(
                d.usbDevice.vendorId, d.usbDevice.productId,
                d.usbDevice.productName ?: "mass storage", usb.hasPermission(d.usbDevice)
            )
        }
    }

    fun open(context: Context, vidPid: String): String {
        if (vidPid.isBlank()) return "ERR usage: flash open vid:pid\n"
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        registerReceivers(app)
        val dev = UsbMassStorageDevice.getMassStorageDevices(app).firstOrNull {
            "%04x:%04x".format(it.usbDevice.vendorId, it.usbDevice.productId) == vidPid.lowercase()
        } ?: return "ERR no mass storage device $vidPid (run: dapi flash list)\n"

        if (!usb.hasPermission(dev.usbDevice)) {
            pendingVidPid = vidPid.lowercase()
            val pi = PendingIntent.getBroadcast(
                app, 3,
                Intent(ACTION_PERM).setPackage(app.packageName).putExtra("from", "flash"),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            usb.requestPermission(dev.usbDevice, pi)
            return "permission dialog shown - the stick attaches automatically after allowing\ncheck: dapi flash info\n"
        }

        closeInternal()
        return try {
            dev.init()
            val parts = dev.partitions
            if (parts.isEmpty()) {
                if (dev.rawBlockDevice == null) {
                    runCatching { dev.close() }
                    "ERR opened but no readable storage LUN (no card/medium inserted?)\n"
                } else {
                    // attach anyway: format works at the raw block level
                    device = dev
                    rootFile = null
                    openVidPid = vidPid.lowercase()
                    "OK attached - NO FAT32 filesystem (NTFS/exFAT or unformatted)\n" +
                        "quick-format as FAT32 (ERASES EVERYTHING): dapi flash format CONFIRM [LABEL]\n"
                }
            } else {
                device = dev
                rootFile = parts[0].fileSystem.rootDirectory
                openVidPid = vidPid.lowercase()
                startTransferServer()
                "OK " + parts[0].fileSystem.javaClass.simpleName + " attached - file transfers on 127.0.0.1:" + XFER_PORT + "\n"
            }
        } catch (e: Exception) {
            runCatching { dev.close() }
            "ERR ${e.message ?: e.javaClass.simpleName}\n"
        }
    }

    fun info(): String {
        synchronized(lock) {
            val dev = device ?: return notOpen
            val f = dev.partitions.firstOrNull()?.fileSystem
                ?: return buildString {
                    append("device: ").append(openVidPid).append('\n')
                    dev.rawBlockDevice?.let { raw ->
                        append("raw capacity: ")
                            .append(raw.blocks * raw.blockSize / (1024 * 1024))
                            .append(" MB (sector ").append(raw.blockSize).append("B)\n")
                    }
                    append("filesystem: NONE - quick-format with: dapi flash format CONFIRM [LABEL]\n")
                }
            return buildString {
                append("device: ").append(openVidPid).append('\n')
                append("filesystem: ").append(f.javaClass.simpleName).append('\n')
                append("label: ").append(f.volumeLabel).append('\n')
                append("capacity: ").append(f.capacity / (1024 * 1024)).append(" MB\n")
                append("used: ").append(f.occupiedSpace / (1024 * 1024)).append(" MB, free: ")
                    .append(f.freeSpace / (1024 * 1024)).append(" MB\n")
            }
        }
    }

    fun ls(path: String): String {
        val root = rootFile ?: return noRootMsg()
        val dir = resolve(root, path) ?: return "ERR not found: $path\n"
        if (!dir.isDirectory) return "- ${dir.name}  ${dir.length} bytes\n"
        val names = dir.list()
        val files = dir.listFiles()
        if (names.isEmpty()) return "(empty)\n"
        return names.mapIndexed { i, n ->
            if (files[i].isDirectory) "d $n/\n" else "- $n  ${files[i].length} bytes\n"
        }.joinToString("")
    }

    /** Reads a small file - raw bytes, streamed straight through the 8037 response. */
    fun cat(path: String): ByteArray {
        val root = rootFile ?: return noRootMsg().toByteArray()
        val f = resolve(root, path) ?: return ("ERR not found: $path\n").toByteArray()
        if (f.isDirectory) return ("ERR $path is a directory\n").toByteArray()
        if (f.length > CAT_LIMIT)
            return ("ERR too big for cat (${f.length} bytes) - use: dapi flash get \"$path\"\n").toByteArray()
        return try {
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(64 * 1024)
            var off = 0L
            while (off < f.length) {
                buf.clear()
                f.read(off, buf)
                out.write(buf.array(), 0, buf.position())
                if (buf.position() == 0) break
                off += buf.position()
            }
            out.toByteArray()
        } catch (e: Exception) {
            ("ERR " + (e.message ?: e.javaClass.simpleName) + "\n").toByteArray()
        }
    }

    fun mkdir(path: String): String {
        val root = rootFile ?: return noRootMsg()
        val p = path.trim().trimEnd('/')
        if (p.isBlank() || p == "/") return "ERR usage: flash mkdir /path/dir\n"
        if (resolve(root, p) != null) return "ERR already exists: $p\n"
        val parentPath = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        val parent = if (parentPath.isBlank()) root
        else resolve(root, parentPath) ?: return "ERR parent not found: $parentPath\n"
        if (!parent.isDirectory) return "ERR parent is not a directory\n"
        parent.createDirectory(name)
        return "OK created $p\n"
    }

    fun rm(path: String): String {
        val root = rootFile ?: return noRootMsg()
        val f = resolve(root, path) ?: return "ERR not found: $path\n"
        f.delete()
        return "OK deleted $path\n"
    }

    fun eject(): String {
        closeInternal()
        return "OK ejected\n"
    }

    /**
     * Quick-formats the attached stick as FAT32 (MBR, one 1 MiB-aligned
     * partition). ERASES EVERYTHING - hence the CONFIRM argument.
     */
    fun format(context: Context, confirm: String, label: String): String {
        if (confirm != "CONFIRM")
            return "ERR formatting ERASES THE ENTIRE STICK\nconfirm with: dapi flash format CONFIRM [LABEL]\n"
        synchronized(lock) {
            val dev = device ?: return notOpen
            val raw = dev.rawBlockDevice
                ?: return "ERR no raw block device (no readable LUN)\n"
            val vid = openVidPid
            val result = try {
                Fat32Formatter.quickFormat(raw, label)
            } catch (e: Exception) {
                return "ERR format failed: ${e.message ?: e.javaClass.simpleName}\n"
            }
            closeInternal()
            // re-attach with the fresh filesystem
            val app = context.applicationContext
            val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
            val reopen = runCatching {
                val dev2 = UsbMassStorageDevice.getMassStorageDevices(app).firstOrNull {
                    "%04x:%04x".format(it.usbDevice.vendorId, it.usbDevice.productId) == vid
                }
                if (dev2 != null && usb.hasPermission(dev2.usbDevice)) {
                    dev2.init()
                    if (dev2.partitions.isNotEmpty()) {
                        device = dev2
                        rootFile = dev2.partitions[0].fileSystem.rootDirectory
                        startTransferServer()
                        "re-attached: ${dev2.partitions[0].fileSystem.javaClass.simpleName}\n"
                    } else "WARN could not re-attach\n"
                } else "WARN could not re-attach\n"
            }.getOrElse { "WARN could not re-attach\n" }
            return result + reopen
        }
    }

    /**
     * Multi-filesystem format (fat16/exfat/ntfs/ext2/3/4): runs the real mkfs
     * inside Ubuntu on a sparse image and copies the result to the stick.
     * Long job -> runs on a background thread; result arrives as a
     * notification (phone + Telegram) so the terminal is not blocked.
     */
    fun formatMulti(context: Context, type: String, confirm: String, label: String): String {
        if (confirm != "CONFIRM")
            return "ERR formatting ERASES THE ENTIRE STICK\n" +
                "confirm with: dapi flash format $type CONFIRM [LABEL]\n"
        if (!ImageFormatter.isKnown(type))
            return "ERR unknown type '$type' - use fat16|exfat|ntfs|ext2|ext3|ext4\n"
        val app = context.applicationContext
        synchronized(lock) {
            if (imageFormatBusy)
                return "ERR a format is already running - wait for its notification\n"
            val dev = device ?: return notOpen
            val raw = dev.rawBlockDevice
                ?: return "ERR no raw block device (no readable LUN)\n"
            imageFormatBusy = true
            // stop accepting new file transfers while we own the raw device
            runCatching { transferServer?.close() }

            Thread {
                val result = runCatching { ImageFormatter.format(app, raw, type, label) }
                    .getOrElse { "ERR format failed: ${it.message ?: it.javaClass.simpleName}\n" }
                // the stick is no longer FAT32 - detach it completely
                runCatching { closeInternal() }
                imageFormatBusy = false
                val ok = result.startsWith("OK")
                AndroidApiBridge.notifyUser(
                    app,
                    if (ok) "✅ الفلاشة صارت ${type.uppercase()}" else "❌ فشل الفورمات",
                    if (ok)
                        "اكتمل بنجاح — يمكنك فصل الفلاشة الآن واستخدامها في أي جهاز"
                    else
                        result.take(400) + "\n\n💡 افصل الفلاشة ووصّلها من جديد ثم أعد المحاولة"
                )
            }.apply { isDaemon = true; name = "ut-img-format" }.start()
        }
        return "OK formatting to ${type.uppercase()} started in background\n" +
            "the real mkfs runs inside Ubuntu (missing tools auto-install first)\n" +
            "DO NOT unplug the stick - a notification arrives when it finishes\n"
    }

    // -------------------------------------------------------------- transfer TCP

    private fun startTransferServer() {
        synchronized(lock) {
            if (running.get()) return
            running.set(true)
            transferServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", XFER_PORT))
            }
        }
        Thread {
            while (running.get()) {
                val client = try {
                    transferServer?.accept()
                } catch (_: Exception) {
                    break
                } ?: break
                Thread { handleTransfer(client) }
                    .apply { name = "flash-xfer"; isDaemon = true; start() }
            }
        }.apply { name = "flash-xfer-accept"; isDaemon = true; start() }
    }

    /**
     * Streaming transfer protocol on 8041 (byte-precise - never buffered):
     *   "get\tREMOTE\n"                     -> raw file bytes until close
     *   "put\tREMOTE\tSIZE\n" + SIZE bytes   -> "OK written N bytes\n"
     */
    private fun handleTransfer(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 30_000
                val input = c.getInputStream()
                // read the header line byte-by-byte so the payload is untouched
                val line = ByteArrayOutputStream()
                var b = input.read()
                while (b != -1 && b != '\n'.code && b != '\r'.code) {
                    line.write(b)
                    b = input.read()
                }
                if (b == '\r'.code) input.read()
                val parts = line.toString(Charsets.UTF_8.name()).split('\t')
                when (parts.firstOrNull()) {
                    "get" -> {
                        val root = rootFile
                        val f = root?.let { resolve(it, parts.getOrElse(1) { "" }) }
                        if (root == null) {
                            respond(c, noRootMsg())
                        } else if (f == null || f.isDirectory) {
                            respond(c, "ERR get failed (not found)\n")
                        } else {
                            val out = c.getOutputStream()
                            val buf = ByteBuffer.allocate(256 * 1024)
                            var off = 0L
                            while (off < f.length) {
                                buf.clear()
                                f.read(off, buf)
                                if (buf.position() == 0) break
                                out.write(buf.array(), 0, buf.position())
                                off += buf.position()
                            }
                            out.flush()
                        }
                    }
                    "put" -> {
                        val remote = parts.getOrElse(1) { "" }
                        val size = parts.getOrNull(2)?.toLongOrNull() ?: -1L
                        if (remote.isBlank() || size < 0) {
                            respond(c, "ERR usage: put REMOTE SIZE\n"); return
                        }
                        val root = rootFile ?: run { respond(c, notOpen); return }
                        runCatching { resolve(root, remote)?.delete() }
                        val clean = remote.trimEnd('/')
                        val name = clean.substringAfterLast('/')
                        val dirPath = clean.substringBeforeLast('/', "")
                        val dir = if (dirPath.isBlank()) root
                        else resolve(root, dirPath) ?: run { respond(c, "ERR directory not found: $dirPath\n"); return }
                        if (!dir.isDirectory) {
                            respond(c, "ERR not a directory: $dirPath\n"); return
                        }
                        val f = dir.createFile(name)
                        val buf = ByteArray(256 * 1024)
                        var remaining = size
                        var off = 0L
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            f.write(off, ByteBuffer.wrap(buf, 0, n))
                            off += n
                            remaining -= n
                        }
                        f.flush()
                        f.close()
                        respond(c, if (remaining > 0) "WARN short write ($off bytes)\n"
                        else "OK written $off bytes to $remote\n")
                    }
                    else -> respond(c, "ERR unknown transfer command (get|put)\n")
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun respond(c: Socket, msg: String) {
        runCatching {
            c.getOutputStream().apply { write(msg.toByteArray(Charsets.UTF_8)); flush() }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun resolve(root: UsbFile, path: String): UsbFile? {
        val p = path.trim()
        if (p.isBlank() || p == "/") return root
        return root.search(p.trimEnd('/'))
    }

    private fun closeInternal() {
        running.set(false)
        runCatching { device?.close() }
        device = null
        rootFile = null
        runCatching { transferServer?.close() }
        transferServer = null
    }

    private fun registerReceivers(app: Context) {
        if (receiversRegistered) return
        receiversRegistered = true

        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra("from") != "flash") return
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
                val vid = pendingVidPid ?: return
                open(c, vid)
            }
        }, IntentFilter(ACTION_PERM))

        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    ?: return
                if ("%04x:%04x".format(dev.vendorId, dev.productId) == openVidPid) {
                    closeInternal()
                }
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }
}

/**
 * Minimal, careful FAT32 quick-format for [BlockDeviceDriver]s whose offsets
 * are logical block addresses. Layout: MBR -> 1 MiB gap -> VBR (32 reserved
 * sectors, 2 FATs) -> data. Writes valid BPB + FSInfo + backup copies and
 * zeroes both FATs and the root directory cluster.
 */
private object Fat32Formatter {

    fun quickFormat(raw: BlockDeviceDriver, labelArg: String): String {
        val bs = raw.blockSize
        require(bs == 512) { "unsupported block size: $bs" }
        val totalSectors = raw.blocks
        require(totalSectors > 2048 + 65536) { "device too small for FAT32 (< ~33 MB)" }

        val partStart = 2048L                          // 1 MiB alignment
        val partSectors = totalSectors - partStart
        val spc = when {                               // sectors per cluster
            partSectors < 262_144L -> 1L               // < 128 MB
            partSectors < 524_288L -> 2L               // < 256 MB
            partSectors < 16_777_216L -> 4L            // < 8 GB
            partSectors < 33_554_432L -> 8L            // < 16 GB
            partSectors < 67_108_864L -> 16L           // < 32 GB
            else -> 32L
        }
        val reserved = 32L
        val numFats = 2L
        var fatSectors = 1L
        var clusters = 0L
        repeat(6) {
            clusters = (partSectors - reserved - numFats * fatSectors) / spc
            fatSectors = (clusters + 2) * 4 / 512 + 1
        }
        clusters = (partSectors - reserved - numFats * fatSectors) / spc
        require(clusters >= 65_525) { "FAT32 needs >= 65525 clusters (got $clusters)" }

        val fatStart = partStart + reserved
        val dataStart = fatStart + numFats * fatSectors
        val label = labelArg.uppercase().take(11).padEnd(11)

        // ---- MBR with one FAT32 (LBA) partition entry
        val mbr = ByteArray(512)
        mbr[0] = 0x80.toByte()                          // bootable
        mbr[1] = 0xFE.toByte(); mbr[2] = 0xFF.toByte(); mbr[3] = 0xFF.toByte()
        mbr[4] = 0x0C                                   // FAT32 LBA type
        mbr[5] = 0xFE.toByte(); mbr[6] = 0xFF.toByte(); mbr[7] = 0xFF.toByte()
        putLe32(mbr, 8, partStart.toInt())
        putLe32(mbr, 12, partSectors.toInt())
        mbr[510] = 0x55.toByte(); mbr[511] = 0xAA.toByte()
        raw.write(0, ByteBuffer.wrap(mbr))

        // ---- FAT32 boot sector (BPB)
        val bpb = ByteArray(512)
        bpb[0] = 0xEB.toByte(); bpb[1] = 0x58.toByte(); bpb[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray().copyInto(bpb, 3)
        putLe16(bpb, 11, 512)                           // bytes per sector
        bpb[13] = spc.toByte()                          // sectors per cluster
        putLe16(bpb, 14, reserved.toInt())
        bpb[16] = numFats.toByte()
        putLe16(bpb, 17, 0)                             // root entries (FAT12/16 only)
        putLe16(bpb, 19, 0)                             // totSec16
        bpb[21] = 0xF8.toByte()                         // media
        putLe16(bpb, 22, 0)                             // fatSz16
        putLe16(bpb, 24, 63)                            // sectors per track
        putLe16(bpb, 26, 255)                           // heads
        putLe32(bpb, 28, partStart.toInt())                     // hidden sectors
        putLe32(bpb, 32, partSectors.toInt())                   // totSec32
        putLe32(bpb, 36, fatSectors.toInt())                    // fatSz32
        putLe16(bpb, 40, 0)                             // extFlags
        putLe16(bpb, 42, 0)                             // fsVersion
        putLe32(bpb, 44, 2)                             // root cluster
        putLe16(bpb, 48, 1)                             // fsInfo sector
        putLe16(bpb, 50, 6)                             // backup boot sector
        bpb[64] = 0x80.toByte()                         // drive number
        bpb[66] = 0x29.toByte()                         // boot signature
        putLe32(bpb, 67, (System.currentTimeMillis() / 1000).toInt())
        label.toByteArray().copyInto(bpb, 71)
        "FAT32   ".toByteArray().copyInto(bpb, 82)
        bpb[510] = 0x55.toByte(); bpb[511] = 0xAA.toByte()
        raw.write(partStart, ByteBuffer.wrap(bpb))
        raw.write(partStart + 6, ByteBuffer.wrap(bpb))  // backup boot sector

        // ---- FSInfo sectors
        val fsi = ByteArray(512)
        "RRaA".toByteArray().copyInto(fsi, 0)
        "rrAa".toByteArray().copyInto(fsi, 484)
        putLe32(fsi, 488, (clusters - 1).toInt())       // free cluster count
        putLe32(fsi, 492, 2)                            // next free cluster
        fsi[510] = 0x55.toByte(); fsi[511] = 0xAA.toByte()
        raw.write(partStart + 1, ByteBuffer.wrap(fsi))
        raw.write(partStart + 7, ByteBuffer.wrap(fsi))  // backup FSInfo

        // ---- both FATs: header + zeroed body
        val fatHead = ByteArray(512)
        putLe32(fatHead, 0, -0x8)                       // 0xFFFFFFF8 + media
        putLe32(fatHead, 4, -0x1)                       // 0xFFFFFFFF
        putLe32(fatHead, 8, -0x1)                       // root dir EOC
        val zeros = ByteArray(128 * 512)
        for (f in 0 until numFats) {
            val base = fatStart + f * fatSectors
            raw.write(base, ByteBuffer.wrap(fatHead))
            var s = 1L
            while (s < fatSectors) {
                val n = minOf(128L, fatSectors - s)
                raw.write(base + s, ByteBuffer.wrap(zeros, 0, (n * 512).toInt()))
                s += n
            }
        }

        // ---- empty root directory (cluster 2)
        raw.write(dataStart, ByteBuffer.wrap(ByteArray(512)))

        return "OK quick-formatted FAT32: ${(partSectors / 2048)} MB, " +
            "cluster=${spc * 512}B, clusters=$clusters, label=$label\n"
    }

    private fun putLe16(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putLe32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
        a[off + 2] = ((v shr 16) and 0xFF).toByte()
        a[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
