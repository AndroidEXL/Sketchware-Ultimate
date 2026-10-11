package com.example.ubuntuterminal

import pro.sketchware.R

import android.content.Context
import com.example.ubuntuterminal.setup.Installer
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Formats a USB stick with a real mkfs.<type> running inside the Ubuntu
 * container. mkfs writes into a sparse image file (so nothing is ever
 * actually stored on the phone), then only the *written* extents of that
 * image are copied to the stick's raw block device, offset by a 1 MiB
 * MBR partition start.
 *
 * Supported: fat16, exfat, ntfs, ext2, ext3, ext4.  (fat32 stays on the
 * built-in quick formatter - it is instant and needs no packages.)
 */
object ImageFormatter {

    private class Mfs(val bin: String, val extra: String, val pkg: String, val mbrType: Int)

    private val TYPES = mapOf(
        "fat16" to Mfs("mkfs.vfat", "-F 16", "dosfstools", 0x0E),
        "exfat" to Mfs("mkfs.exfat", "", "exfatprogs", 0x07),
        "ntfs" to Mfs("mkntfs", "-Q -F", "ntfs-3g", 0x07),
        "ext2" to Mfs("mkfs.ext2", "-F", "e2fsprogs", 0x83),
        "ext3" to Mfs("mkfs.ext3", "-F", "e2fsprogs", 0x83),
        "ext4" to Mfs("mkfs.ext4", "-F", "e2fsprogs", 0x83)
    )

    fun isKnown(type: String) = TYPES.containsKey(type.lowercase())

    /**
     * Makes sure [bin] exists inside Ubuntu - if not, AUTO-INSTALLS [pkg]
     * via apt (with an `apt update` fallback for stale package lists and a
     * dpkg-lock wait in case the user is running apt in a session).
     * Returns null on success or an "ERR ..." message for the user.
     */
    private fun ensureTool(app: Context, bin: String, pkg: String): String? {
        val (rc0, _) = TerminalManager.execInUbuntu(app, "command -v $bin", 60)
        if (rc0 == 0) return null

        // tell the user what is happening - the install can take a while
        // v1.30: package notifications respect the settings toggle
        // (extinguished by default).
        if (AndroidApiBridge.pkgNotifyEnabled(app)) {
            AndroidApiBridge.notifyUser(
                app, "⏳ تثبيت $pkg",
                "الأداة $bin مش موجودة داخل أوبونتو — جاري تثبيتها تلقائياً (apt)...\nالفورمات هيكمل لوحده بعد التثبيت"
            )
        }
        val installCmd = "DEBIAN_FRONTEND=noninteractive apt-get " +
            "-o Dpkg::Lock::Timeout=90 -o Acquire::Retries=3 install -y -qq $pkg 2>&1"
        var r = TerminalManager.execInUbuntu(app, installCmd, 900)
        if (r.first != 0) {
            // package lists probably empty/stale -> update once, retry
            TerminalManager.execInUbuntu(app, "apt-get update -qq 2>&1", 900)
            r = TerminalManager.execInUbuntu(app, installCmd, 900)
        }
        return if (r.first == 0) null else
            "ERR تعذّر تثبيت $pkg تلقائياً\n" +
            "شغّل ده في التيرمنال وجرّب تاني:\n" +
            "  apt update && apt install -y $pkg\n\n" +
            "السبب:\n" + r.second.take(500) + "\n"
    }

    /**
     * BLOCKS until done - run on a worker thread. The device must stay
     * attached for the whole call. Returns an "OK ..." or "ERR ..." line.
     */
    fun format(app: Context, raw: BlockDeviceDriver, type: String, label: String): String {
        val mfs = TYPES[type.lowercase()] ?: return "ERR unknown type '$type'\n"
        val bs = raw.blockSize
        if (bs != 512) return "ERR unsupported block size: $bs\n"
        val totalSectors = raw.blocks
        if (totalSectors <= 4096) return "ERR stick too small\n"
        val partStart = 2048L                         // 1 MiB alignment
        val partSectors = totalSectors - partStart

        // 0. make sure the mkfs tool exists (auto-install if missing) and
        //    deploy our private extents helper - ut-extents uses
        //    SEEK_DATA/SEEK_HOLE which works unprivileged even on Android's
        //    ENCRYPTED /data, where FIEMAP/FIBMAP are blocked for apps
        ensureTool(app, mfs.bin, mfs.pkg)?.let { return it }
        deployExtentsHelper(app)?.let { return it }

        // v1.29: the image now lives in the app's CACHE dir, not in the
        // rootfs. It is a huge sparse file (full partition size); storage
        // cleaners saw it inside the app's data dir, read "60+ GB of app
        // data", and some of them wiped the app's data - the user then lost
        // their whole Ubuntu. cacheDir is never counted as user data and can
        // be cleaned safely. Legacy stray image from v1.28 is removed too.
        val fmtDir = File(app.cacheDir, "fmt")
        val img = File(fmtDir, "fmt.img")
        runCatching { fmtDir.mkdirs() }
        runCatching { img.delete() }
        runCatching { File(Installer.rootfsDir(app), "tmp/fmt.img").delete() }
        try {
            // 1. sparse image sized exactly like the future partition
            RandomAccessFile(img, "rw").use { it.setLength(partSectors * 512) }

            // 2. run the real mkfs inside Ubuntu on the image
            val lbl = label.trim().replace("'", "").replace("\"", "")
            val guestPath = "/var/tmp/fmt.img"
            val fmtBind = fmtDir.absolutePath to "/var/tmp"
            val labelArg = when {
                lbl.isEmpty() -> ""
                mfs.bin == "mkfs.vfat" -> " -n '${lbl.take(11)}'"
                else -> " -L '${lbl.take(32)}'"
            }
            val (rc, out) = TerminalManager.execInUbuntu(
                app, "LC_ALL=C ${mfs.bin} ${mfs.extra}${labelArg} $guestPath 2>&1",
                600, fmtBind
            )
            if (rc != 0)
                return "ERR ${mfs.bin} failed:\n" + out.take(600) + "\n"

            // 3. ask which extents of the image were actually written
            val (rc2, out2) = TerminalManager.execInUbuntu(
                app, "LC_ALL=C /usr/local/bin/ut-extents $guestPath 2>&1", 120, fmtBind
            )
            if (rc2 != 0)
                return "ERR ut-extents failed:\n" + out2.take(400) + "\n"
            val extents = parseExtents(out2)
            if (extents.isEmpty()) return "ERR no written extents found in the image\n" + out2.take(400) + "\n"

            // 4. MBR with one partition entry covering the image
            val mbr = ByteArray(512)
            mbr[0] = 0x80.toByte()                           // bootable
            mbr[1] = 0xFE.toByte(); mbr[2] = 0xFF.toByte(); mbr[3] = 0xFF.toByte()
            mbr[4] = mfs.mbrType.toByte()
            mbr[5] = 0xFE.toByte(); mbr[6] = 0xFF.toByte(); mbr[7] = 0xFF.toByte()
            putLe32(mbr, 8, partStart)
            putLe32(mbr, 12, partSectors)
            mbr[510] = 0x55.toByte(); mbr[511] = 0xAA.toByte()
            raw.write(0, ByteBuffer.wrap(mbr))

            // 5. copy the written extents, offset by the partition start.
            //    32 KiB chunks: one huge SCSI WRITE(10) stalls some sticks and
            //    Android USB stacks (MAX_RECOVERY_ATTEMPTS failures); this is
            //    the same size class as libaums' own cluster writes, which are
            //    proven to work on real hardware. Every chunk gets its own
            //    retries on top of the transport-level recovery.
            var copied = 0L
            val chunk = 32 * 1024
            RandomAccessFile(img, "r").use { f ->
                val buf = ByteArray(chunk)
                for ((startSector, lenSectors) in extents) {
                    val total = lenSectors * 512
                    var off = 0L
                    while (off < total) {
                        val n = minOf(chunk.toLong(), total - off).toInt()
                        f.seek(startSector * 512 + off)
                        f.readFully(buf, 0, n)
                        var attempt = 0
                        while (true) {
                            try {
                                raw.write(partStart + startSector + off / 512, ByteBuffer.wrap(buf, 0, n))
                                break
                            } catch (e: java.io.IOException) {
                                attempt++
                                if (attempt >= 4)
                                    throw java.io.IOException(
                                        "USB write failed at sector ${partStart + startSector + off / 512} " +
                                            "(${copied / 1024} KiB written so far): ${e.message}", e)
                                Thread.sleep(400)
                            }
                        }
                        off += n
                        copied += n
                    }
                }
            }
            val mb = copied / (1024 * 1024)
            return "OK formatted as ${type.uppercase()} - $mb MiB of filesystem metadata written\n"
        } finally {
            runCatching { img.delete() }
        }
    }

    /** Parses `ut-extents` output lines: "startSector lenSectors" (512b sectors). */
    private fun parseExtents(out: String): List<Pair<Long, Long>> {
        val res = ArrayList<Pair<Long, Long>>()
        for (line in out.lineSequence()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size != 2) continue
            val s = p[0].toLongOrNull() ?: continue
            val n = p[1].toLongOrNull() ?: continue
            if (n > 0) res.add(s to n)
        }
        return res
    }

    /**
     * Extracts the bundled ut-extents binary (static aarch64, SEEK_DATA /
     * SEEK_HOLE scanner) into the Ubuntu rootfs. Returns null on success.
     */
    private fun deployExtentsHelper(app: Context): String? {
        return runCatching {
            val out = File(Installer.rootfsDir(app), "usr/local/bin/ut-extents")
            out.parentFile?.mkdirs()
            app.resources.openRawResource(R.raw.ut_extents).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.setReadable(true, false)
            out.setExecutable(true, false)
            null
        }.getOrElse { "ERR could not deploy ut-extents: ${it.message}\n" }
    }

    private fun putLe32(a: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) a[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
