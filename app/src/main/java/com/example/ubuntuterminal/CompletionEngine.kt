package com.example.ubuntuterminal

import pro.sketchware.R

import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.ubuntuterminal.setup.Installer
import java.io.File

/**
 * Live completion bar for the terminal: as you type `apt install gi...` a row
 * of suggestion chips appears above the extra keys - tap one and the rest of
 * the word is typed for you.
 *
 * Knows three things:
 *  - shell commands (builtin list) while typing the first word
 *  - subcommands of apt / apt-get / pkg / dapi while typing the second word
 *  - package names (prefix match) after `apt|pkg install|remove|search ...`,
 *    read from var/lib/dapi-pkgs.txt inside the rootfs (kept fresh by an
 *    APT::Update::Post-Invoke hook, `pkg update` and `dapi pkglist`).
 *
 * The typed line is reconstructed from key events (printable chars append,
 * backspace deletes, enter resets, any other control key resets), which is
 * accurate for the common typing flow.
 */
class CompletionEngine(private val activity: MainActivity) {

    private val scroll: HorizontalScrollView = activity.findViewById(R.id.completeScroll)
    private val chips: LinearLayout = activity.findViewById(R.id.completeChips)
    private val line = StringBuilder()

    @Volatile private var pkgNames: List<String> = emptyList()
    @Volatile private var loadedAt = 0L

    private val commands = listOf(
        "apt", "apt-get", "pkg", "dpkg", "dapi", "ls", "cd", "pwd", "cat", "nano",
        "grep", "find", "tar", "wget", "curl", "git", "python3", "node", "npm",
        "pip", "tmux", "htop", "top", "df", "du", "free", "uname", "whoami",
        "clear", "history", "man", "mkdir", "rmdir", "rm", "cp", "mv", "chmod",
        "chown", "echo", "export", "source", "ssh", "scp", "socat", "tmate",
        "less", "head", "tail", "sort", "wc", "sed", "awk", "ps", "kill",
        "which", "env", "alias", "touch", "ping", "ip"
    )

    private val subcommands = mapOf(
        "apt" to listOf(
            "install", "remove", "purge", "update", "upgrade", "full-upgrade",
            "autoremove", "search", "show", "list", "edit-sources", "clean"
        ),
        "apt-get" to listOf(
            "install", "remove", "purge", "update", "upgrade", "dist-upgrade",
            "autoremove", "clean"
        ),
        "pkg" to listOf(
            "install", "remove", "purge", "reinstall", "autoremove", "update",
            "upgrade", "full-upgrade", "search", "show", "files", "list-all",
            "list-installed", "clean", "autoclean"
        ),
        "dapi" to listOf(
            "ping", "battery", "usb", "usbp", "info", "vibrate", "notify",
            "toast", "torch", "flash", "usbraw", "usbraw", "usbser", "share",
            "tg", "vnc", "pkglist"
        )
    )

    private val packageCtx = setOf("install", "remove", "purge", "reinstall", "search", "show")

    init {
        Thread { loadPkgs(force = true) }.start()
    }

    // ------------------------------------------------------------- input events

    fun onChar(codePoint: Int, ctrlDown: Boolean) {
        if (ctrlDown || activity.extraKeys.isAltActive ||
            codePoint < 0x20 || codePoint == 0x7F
        ) {
            line.setLength(0)
            render(emptyList())
            return
        }
        line.appendCodePoint(codePoint)
        update()
    }

    fun onKey(keyCode: Int, e: KeyEvent) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (line.isNotEmpty()) line.deleteCharAt(line.length - 1)
                update()
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                line.setLength(0)
                render(emptyList())
                Thread { loadPkgs(force = false) }.start()
            }
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_ESCAPE -> {
                line.setLength(0)
                render(emptyList())
            }
            else -> Unit // printable keys arrive via onCodePoint
        }
    }

    /** text injected by the extra-keys row (TAB, arrows, ...) or paste */
    fun onRawWrite(text: String) {
        if (text.isEmpty()) return
        if (text.any { it.code < 0x20 }) {
            line.setLength(0)
            render(emptyList())
        } else {
            line.append(text)
            update()
        }
    }

    fun onPaste(text: String) = onRawWrite(text.replace("\r", "\n"))

    // ------------------------------------------------------------- suggestions

    private fun update() = render(suggest())

    private fun suggest(): List<String> {
        if (line.isEmpty()) return emptyList()
        val l = line.toString()
        if (l.contains('\n') || l.contains('\r')) return emptyList()
        if (l.endsWith(" ")) return emptyList() // only complete the word being typed
        val tokens = l.trimStart().split(Regex("\\s+"))
        val partial = tokens.lastOrNull() ?: return emptyList()
        if (partial.isEmpty()) return emptyList()
        val head = tokens.dropLast(1).filter { it.isNotBlank() }
        val pkgMgr = setOf("apt", "apt-get", "pkg")
        return when {
            head.isEmpty() ->
                commands.filter { it.startsWith(partial) }.take(10)
            head.size == 1 && subcommands.containsKey(head[0]) ->
                subcommands[head[0]]!!.filter { it.startsWith(partial) }.take(10)
            head.size >= 2 && head[0] in pkgMgr && head[1] in packageCtx ->
                completePkgs(partial)
            else -> emptyList()
        }
    }

    /**
     * Package completion: everything that STARTS WITH the fragment first
     * (alphabetical), then everything that CONTAINS it - so typing "python"
     * also finds libpython3.12, golang-python-dev, python3-pip ...
     */
    private fun completePkgs(partial: String): List<String> {
        val p = partial.lowercase()
        if (p.isEmpty()) return emptyList()
        val byPrefix = ArrayList<String>()
        val byContains = ArrayList<String>()
        for (n in pkgNames) {
            if (n.startsWith(p)) {
                if (byPrefix.size < 30) byPrefix.add(n)
            } else if (n.contains(p)) {
                if (byContains.size < 30) byContains.add(n)
            }
            if (byPrefix.size >= 30 && byContains.size >= 30) break
        }
        val out = ArrayList<String>(byPrefix)
        out.addAll(byContains)
        return out.take(30)
    }

    /** chip tapped: type the remaining part of the suggestion into the shell */
    private fun pick(suggestion: String) {
        val l = line.toString()
        val partial = l.trimStart().split(Regex("\\s+")).lastOrNull() ?: return
        if (partial.isEmpty()) return
        if (suggestion.startsWith(partial)) {
            val remaining = suggestion.substring(partial.length)
            if (remaining.isEmpty()) return
            TerminalManager.activeSession?.write(remaining)
            line.append(remaining)
        } else {
            // contains-match: erase the typed fragment and type the full name
            TerminalManager.activeSession?.write("\u0008".repeat(partial.length) + suggestion)
            line.setLength(line.length - partial.length)
            line.append(suggestion)
        }
        update()
    }

    // ------------------------------------------------------------- package list

    private fun loadPkgs(force: Boolean) {
        runCatching {
            val f = File(Installer.rootfsDir(activity), "var/lib/dapi-pkgs.txt")
            if (!f.exists()) return
            val mtime = f.lastModified()
            if (!force && mtime == loadedAt) return
            val names = f.readLines().filter { it.isNotEmpty() }.sorted()
            if (names.isNotEmpty()) {
                pkgNames = names
                loadedAt = mtime
            }
        }
    }

    // ------------------------------------------------------------- chips UI

    private fun render(list: List<String>) {
        activity.runOnUiThread {
            if (list.isEmpty()) {
                scroll.visibility = View.GONE
                return@runOnUiThread
            }
            chips.removeAllViews()
            val d = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            for (s in list) {
                val t = TextView(activity)
                t.text = s
                t.setTextColor(Color.parseColor("#D8E4EE"))
                t.textSize = 13f
                t.maxLines = 1
                t.setPadding(dp(12), dp(6), dp(12), dp(6))
                t.background = ContextCompat.getDrawable(activity, R.drawable.ut_chip_bg)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(6)
                t.layoutParams = lp
                t.setOnClickListener { pick(s) }
                chips.addView(t)
            }
            scroll.visibility = View.VISIBLE
        }
    }
}
