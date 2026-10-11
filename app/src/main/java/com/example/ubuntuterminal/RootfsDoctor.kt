package com.example.ubuntuterminal

import android.content.Context
import android.os.Build
import android.system.Os
import com.example.ubuntuterminal.setup.Installer
import java.io.File
import java.io.FileInputStream

/**
 * Pre-flight checks for the extracted Ubuntu rootfs.
 *
 * PRoot validates the initial guest command with access(path, X_OK) and dies
 * with "proot error: '/usr/bin/env' is not executable" when that check fails.
 * That can mean three different things:
 *
 *  1. the file is missing            → the rootfs must be re-downloaded
 *  2. the executable bit was lost    → repairable by re-applying +x
 *  3. the OS denies exec outright    → SELinux / vendor restriction (report it)
 *
 * [File.canExecute] performs exactly the same access(X_OK) test PRoot uses,
 * and [Os.stat] reveals the raw mode bits, which lets us distinguish (2)
 * from (3) and repair automatically.
 *
 * Ubuntu 26.04 twist: coreutils is the Rust "uutils" multicall binary and all
 * its applets (ls, cut, env, uname, …) are HARDLINKS to usr/bin/coreutils,
 * living under usr/lib/cargo/bin/coreutils/. On devices that forbid creating
 * hardlinks in app storage the extractor falls back to copies/symlinks, and
 * copies created without the tar mode lose their +x bit – so the repair walk
 * must cover the WHOLE rootfs, not just the classic bin directories.
 */
object RootfsDoctor {

    /**
     * Files PRoot + a login shell need. `usr/bin/ls` is a symlink into
     * usr/lib/cargo/bin/coreutils, so probing it also covers the whole
     * coreutils multicall family.
     */
    private val CRITICAL_FILES = listOf("usr/bin/env", "usr/bin/bash", "usr/bin/ls")

    private val REPORT_FILES = listOf(
        "usr/bin/env", "usr/bin/bash", "usr/bin/ls", "usr/bin/apt", "usr/bin/dpkg",
        "usr/lib/cargo/bin/coreutils/env", "etc/resolv.conf"
    )

    enum class Status {
        /** Rootfs is healthy. */
        OK,
        /** Exec bits were missing and have been repaired automatically. */
        REPAIRED,
        /** Core files are missing – the rootfs must be re-downloaded. */
        NEEDS_REINSTALL,
        /** Files are fine but the OS denies executing them (SELinux/vendor). */
        BLOCKED
    }

    data class FileCheck(
        val relPath: String,
        val exists: Boolean,
        /** mode & 0x49 (0x49 == 0o111, the three x-bits) */
        val hasExecBit: Boolean,
        /** access(path, X_OK) – exactly the test PRoot performs */
        val accessOk: Boolean
    )

    fun checkFile(rootfs: File, relPath: String): FileCheck {
        val file = File(rootfs, relPath)
        if (!file.exists()) {
            return FileCheck(relPath, exists = false, hasExecBit = false, accessOk = false)
        }
        val hasBit = try {
            (Os.stat(file.absolutePath).st_mode and 0x49) != 0
        } catch (_: Exception) {
            false
        }
        return FileCheck(relPath, exists = true, hasExecBit = hasBit, accessOk = file.canExecute())
    }

    /**
     * Runs before every session start. The healthy path costs ~6 syscalls.
     * Attempts an automatic repair before giving up.
     *
     * @return the status plus a short human-readable report.
     */
    fun quickCheck(context: Context): Pair<Status, String> {
        val rootfs = Installer.rootfsDir(context)
        var checks = probe(rootfs)

        if (checks.all { it.accessOk }) return Status.OK to format(checks)

        // Unhealthy – repair exec bits across the whole rootfs, then re-probe.
        repairExecBits(context)
        // v1.29: an apt upgrade can leave usr/bin/env or usr/bin/ls missing
        // (they are symlinks into the coreutils multicall tree) - that used
        // to demand a REINSTALL, the path that wiped user data. Recreate
        // them from the multicall binary instead.
        repairCoreutilsLinks(rootfs)
        checks = probe(rootfs)

        return when {
            checks.all { it.accessOk } -> Status.REPAIRED to format(checks)
            checks.any { !it.exists } -> Status.NEEDS_REINSTALL to format(checks)
            else -> Status.BLOCKED to format(checks) // bits set but OS still denies access
        }
    }

    /**
     * v1.29: recreates missing coreutils multicall symlinks
     * (usr/bin/env, usr/bin/ls -> ../lib/cargo/bin/coreutils/&lt;tool&gt;).
     * Also handles dangling links whose target moved during an upgrade.
     * Returns how many links were restored.
     */
    private fun repairCoreutilsLinks(rootfs: File): Int {
        var fixed = 0
        for ((rel, tool) in listOf("usr/bin/env" to "env", "usr/bin/ls" to "ls")) {
            val f = File(rootfs, rel)
            if (f.exists()) continue
            val src = File(rootfs, "usr/lib/cargo/bin/coreutils/$tool")
            if (!src.isFile) continue
            runCatching {
                // remove a possibly dangling link first
                f.delete()
                Os.symlink("../lib/cargo/bin/coreutils/$tool", f.absolutePath)
                fixed++
            }
        }
        return fixed
    }

    private fun probe(rootfs: File): List<FileCheck> =
        CRITICAL_FILES.map { checkFile(rootfs, it) }

    private fun format(checks: List<FileCheck>): String =
        checks.joinToString("\n") {
            "${it.relPath}: " + when {
                !it.exists -> "MISSING"
                it.accessOk -> "OK"
                it.hasExecBit -> "exists, exec-bit set, but execution DENIED by the system"
                else -> "exists, NOT executable"
            }
        }

    /**
     * Re-applies +x to every non-executable file that is actually a program
     * (ELF binary or #! script), anywhere in the rootfs. Returns how many
     * files were fixed. Runs in O(files) with a 4-byte read per candidate –
     * only reached when the quick probe found something wrong.
     */
    fun repairExecBits(context: Context): Int {
        val rootfs = Installer.rootfsDir(context)
        var fixed = 0

        // make sure every top-level directory is traversable
        rootfs.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { it.setExecutable(true, false) }

        rootfs.walkTopDown()
            .onFail { _, _ -> } // skip unreadable directories, keep walking
            .forEach { f ->
                try {
                    if (!f.isFile) return@forEach       // dirs, broken links, …
                    if (f.canExecute()) return@forEach  // already fine
                    if (!looksExecutable(f)) return@forEach
                    if (f.setExecutable(true, false)) fixed++
                } catch (_: Exception) {
                    // keep walking
                }
            }
        return fixed
    }

    /** true when the file starts with the ELF magic or a `#!` shebang. */
    private fun looksExecutable(f: File): Boolean = try {
        FileInputStream(f).use { ins ->
            val b0 = ins.read()
            val b1 = ins.read()
            val b2 = ins.read()
            val b3 = ins.read()
            // 0x7F 'E' 'L' 'F'  or  '#' '!'
            (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) ||
                (b0 == 0x23 && b1 == 0x21)
        }
    } catch (_: Exception) {
        false
    }

    /** Detailed report for the ⋮ menu → "Run diagnostics". */
    fun fullReport(context: Context): String {
        val rootfs = Installer.rootfsDir(context)
        val sb = StringBuilder()
        sb.append("rootfs: ").append(rootfs.absolutePath).append('\n')
        sb.append("installed marker: ").append(File(rootfs, ".installed").isFile).append('\n')

        // single walk: entry count + programs missing their +x bit
        var total = 0
        var brokenExec = 0
        runCatching {
            rootfs.walkTopDown().onFail { _, _ -> }.forEach { f ->
                total++
                if (f.isFile && !f.canExecute() && looksExecutable(f)) brokenExec++
            }
        }
        sb.append("total entries: ").append(total).append('\n')
        sb.append("programs missing +x: ").append(brokenExec).append('\n')

        for (rel in REPORT_FILES) {
            val c = checkFile(rootfs, rel)
            sb.append(rel).append(": ").append(
                when {
                    !c.exists -> "MISSING"
                    c.accessOk -> "OK"
                    c.hasExecBit -> "DENIED (exec-bit is set)"
                    else -> "NOT EXECUTABLE"
                }
            )
            // raw mode + symlink target are gold for remote debugging
            runCatching {
                val mode = Os.stat(File(rootfs, rel).absolutePath).st_mode and 0xFFF
                sb.append(String.format(" (mode %04o)", mode))
            }
            runCatching {
                val target = Os.readlink(File(rootfs, rel).absolutePath)
                sb.append(" -> ").append(target)
            }
            sb.append('\n')
        }
        sb.append("bin -> ")
            .append(runCatching { Os.readlink(File(rootfs, "bin").absolutePath) }.getOrDefault("?"))
            .append('\n')
        sb.append("device abi: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        sb.append("libproot.so: exists=").append(proot.isFile)
            .append(", executable=").append(proot.canExecute())
        return sb.toString()
    }
}
