package com.example.ubuntuterminal.setup

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Downloads the official minimal Ubuntu root filesystem (ubuntu-base) on first
 * launch, extracts it and configures it so it can be run through PRoot.
 */
class Installer(private val context: Context, private val listener: Listener) {

    enum class Stage { DOWNLOAD, EXTRACT, CONFIGURE, DONE }

    interface Listener {
        /** Called on a background thread whenever the current stage changes. */
        fun onStage(stage: Stage)
        /** Called on a background thread; [percent] is 0..100, or -1 when unknown. */
        fun onProgress(percent: Int, detail: String)
    }

    class UnsupportedArchitectureException(message: String) : IOException(message)

    companion object {
        /** Ubuntu release used by this app. */
        const val UBUNTU_VERSION = "26.04.1"
        const val UBUNTU_PRETTY_NAME = "Ubuntu 26.04.1 LTS (Resolute Raccoon)"

        private const val ROOTFS_NAME = "ubuntu-base-$UBUNTU_VERSION-base-%s.tar.gz"

        fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "ubuntu-rootfs")

        /** Persistent home directory, bind-mounted onto /root by PRoot. */
        fun homeDir(ctx: Context): File = File(ctx.filesDir, "ubuntu-home")

        /**
         * v1.29: the rootfs obviously exists even though the marker is gone
         * (storage cleaners, half-finished writes). Whenever this returns
         * true we must NEVER show the install screen - one tap there wipes
         * the user's entire Ubuntu.
         */
        fun looksInstalled(ctx: Context): Boolean {
            val rootfs = rootfsDir(ctx)
            return File(rootfs, "usr/bin/bash").isFile &&
                File(rootfs, "etc").isDirectory &&
                File(rootfs, "bin").isDirectory
        }

        /** v1.29: recreate a lost .installed marker instead of wiping. */
        fun healMarker(ctx: Context) {
            runCatching { File(rootfsDir(ctx), ".installed").writeText("healed\n") }
        }

        fun isInstalled(ctx: Context): Boolean {
            val rootfs = rootfsDir(ctx)
            return File(rootfs, ".installed").isFile && File(rootfs, "usr/bin/bash").isFile
        }

        fun deleteInstall(ctx: Context) {
            rootfsDir(ctx).deleteRecursively()
            homeDir(ctx).deleteRecursively()
        }

        /** Maps a device ABI to the matching ubuntu-base architecture. */
        fun rootfsArch(): String {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            return when (abi) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a", "armeabi" -> "armhf"
                "x86_64" -> "amd64"
                else -> throw UnsupportedArchitectureException(abi)
            }
        }
    }

    /** Runs the whole installation. Throws on any failure. */
    suspend fun install() = withContext(Dispatchers.IO) {
        val arch = rootfsArch()
        val fileName = ROOTFS_NAME.format(Locale.US, arch)

        // Official cdimage server first, the codename alias as a fallback.
        val urls = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/$fileName",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/$fileName"
        )

        val rootfs = rootfsDir(context)
        val home = homeDir(context)
        val archive = File(context.cacheDir, "ubuntu-base.tar.gz")

        // 1) download --------------------------------------------------------
        listener.onStage(Stage.DOWNLOAD)
        download(urls, archive)

        // 2) extract ---------------------------------------------------------
        listener.onStage(Stage.EXTRACT)
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        var lastPercent = -1
        TarGzExtractor.extract(archive, rootfs) { pct ->
            if (pct != lastPercent) { // avoid flooding the UI thread
                lastPercent = pct
                listener.onProgress(pct, "")
            }
        }

        // 3) configure -------------------------------------------------------
        listener.onStage(Stage.CONFIGURE)
        configure(rootfs, home)

        // 4) done ------------------------------------------------------------
        archive.delete()
        listener.onStage(Stage.DONE)
    }

    // ------------------------------------------------------------------ download

    private suspend fun download(urls: List<String>, dest: File) {
        var lastError: IOException? = null
        for (url in urls) {
            try {
                downloadFile(url, dest)
                return
            } catch (e: IOException) {
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private suspend fun downloadFile(url: String, dest: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")

            val total = connection.contentLengthLong // may be -1
            var lastPct = -1

            dest.outputStream().buffered(1 shl 16).use { out ->
                connection.inputStream.buffered(1 shl 16).use { input ->
                    val buf = ByteArray(1 shl 15)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive() // honor cancellation
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                listener.onProgress(pct, humanBytes(done) + " / " + humanBytes(total))
                            }
                        } else {
                            listener.onProgress(-1, humanBytes(done))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // sanity check: must be a gzip archive
        dest.inputStream().use {
            if (it.read() != 0x1f || it.read() != 0x8b) {
                throw IOException("Downloaded file is not a valid gzip archive")
            }
        }
    }

    private fun humanBytes(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    // ------------------------------------------------------------------ config

    private fun configure(rootfs: File, home: File) {
        // DNS inside the container (Android's resolver config is not visible through PRoot)
        writeTextFile(
            File(rootfs, "etc/resolv.conf"),
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )
        writeTextFile(
            File(rootfs, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        )

        // welcome banner shown by every login shell
        writeTextFile(File(rootfs, "etc/profile.d/00-ubuntuterm-welcome.sh"), WELCOME_SCRIPT)

        // persistent home directory mounted on /root
        home.mkdirs()
        writeTextFile(File(home, ".bash_profile"), "[ -f ~/.bashrc ] && . ~/.bashrc\n")
        writeTextFile(File(home, ".bashrc"), BASHRC)

        // mark the installation as complete
        writeTextFile(File(rootfs, ".installed"), UBUNTU_PRETTY_NAME + "\n")
    }

    private fun writeTextFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private val WELCOME_SCRIPT = listOf(
        "# Ubuntu Terminal welcome banner (auto-generated)",
        "if [ -n \"\$PS1\" ]; then",
        "    pretty=\"\$(grep -m1 PRETTY_NAME /etc/os-release | cut -d'\"' -f2)\"",
        "    printf '\\033[01;36m%s\\033[0m\\n' \"  Welcome to \${pretty} on Android (PRoot)\"",
        "    printf '\\033[01;33m%s\\033[0m\\n' \"  Tip: run 'apt update' first - shared storage: /sdcard\"",
        "fi"
    ).joinToString("\n")

    private val BASHRC = listOf(
        "# ~/.bashrc for Ubuntu Terminal (auto-generated)",
        "case \$- in *i*) ;; *) return;; esac",
        "alias ls='ls --color=auto'",
        "alias ll='ls -alF --color=auto'",
        "alias la='ls -A --color=auto'",
        "alias grep='grep --color=auto'",
        "PS1='\\[\\e[1;32m\\]\\u@android\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '"
    ).joinToString("\n")
}
