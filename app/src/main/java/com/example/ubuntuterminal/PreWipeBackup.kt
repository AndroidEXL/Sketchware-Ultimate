package com.example.ubuntuterminal

import android.content.Context
import com.example.ubuntuterminal.setup.Installer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.29 - the data-loss firewall. Before ANY code path is allowed to delete
 * the Ubuntu rootfs, this takes a best-effort backup to
 * /storage/emulated/0/UbuntuTerminal/wipe-backup-<timestamp>/:
 *
 *  - the user's home (capped at 500 MB, skipping .cache)
 *  - dpkg --get-selections + apt-mark showmanual
 *  - /etc/apt (sources, keys, auth)
 *
 * Returns true when the backup directory was created (individual items are
 * best-effort - something is always better than nothing).
 */
object PreWipeBackup {

    private const val HOME_BUDGET = 500L * 1024 * 1024

    fun run(app: Context): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File("/storage/emulated/0/UbuntuTerminal/wipe-backup-$stamp")
        if (!dir.mkdirs() && !dir.isDirectory) return false
        val rootfs = Installer.rootfsDir(app)

        // package lists - best effort, straight from the container
        runCatching {
            TerminalManager.execInUbuntu(app,
                "dpkg --get-selections 2>/dev/null > /var/tmp/ut-selections.txt; " +
                    "apt-mark showmanual 2>/dev/null > /var/tmp/ut-manual.txt", 120)
        }
        copy(File(rootfs, "var/tmp/ut-selections.txt"), dir.resolve("package-selections.txt"))
        copy(File(rootfs, "var/tmp/ut-manual.txt"), dir.resolve("packages-manual.txt"))
        copyTree(File(rootfs, "etc/apt"), dir.resolve("apt-config"))

        val budget = longArrayOf(HOME_BUDGET)
        copyTree(Installer.homeDir(app), dir.resolve("home"), budget)
        return true
    }

    private fun copy(src: File, dst: File) {
        runCatching {
            if (src.isFile) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun copyTree(src: File, dst: File, budget: LongArray? = null) {
        if (!src.exists()) return
        if (!src.isDirectory) {
            val len = runCatching { src.length() }.getOrDefault(0L)
            if (budget != null) {
                if (budget[0] <= 0) return
                budget[0] -= len
            }
            copy(src, dst)
            return
        }
        runCatching { dst.mkdirs() }
        val kids = runCatching { src.listFiles() }.getOrNull() ?: return
        for (k in kids) {
            if (k.name == ".cache") continue
            copyTree(k, dst.resolve(k.name), budget)
        }
    }
}
