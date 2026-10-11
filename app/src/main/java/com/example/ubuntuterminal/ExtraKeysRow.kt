package com.example.ubuntuterminal

import pro.sketchware.R

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The scrollable row of helper keys shown above the soft keyboard:
 * menu, ESC, TAB, CTRL, ALT, SHIFT, arrows, navigation keys, F1-F12 and a
 * few symbols.
 *
 * CTRL, ALT and SHIFT are *sticky one-shot* modifiers with MULTI-SELECT:
 * tap any combination (e.g. CTRL then ALT then SHIFT) - they all light up
 * together - and the next key press is sent with all of them applied,
 * then they reset.
 *
 * Navigation keys, TAB and the symbol keys AUTO-REPEAT when held down
 * (380 ms delay, then every 65 ms) just like a physical keyboard. Sticky
 * modifiers stay applied for the whole hold - hold SHIFT + hold <- and the
 * selection keeps growing.
 * The terminal view consults them via [TermViewClient.readControlKey] /
 * [TermViewClient.readAltKey] and they are consumed in
 * [TermViewClient.onCodePoint] / [TermViewClient.onKeyUp].
 */
class ExtraKeysRow(
    private val activity: AppCompatActivity,
    container: LinearLayout,
    private val onWrite: (String) -> Unit,
    private val onMenu: (View) -> Unit,
    private val onSessions: () -> Unit,
    private val onToggleMouse: (Boolean) -> Unit
) {

    var isCtrlActive = false
        private set
    var isAltActive = false
        private set
    var isShiftActive = false
        private set

    /** True while touch→mouse conversion is enabled (🖱 key, ON by default). */
    var isMouseActive = true
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val repeatJobs = HashMap<View, Runnable>()

    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private var shiftButton: TextView? = null
    private var mouseButton: TextView? = null

    init {
        build(container)
    }

    companion object {
        private const val REPEAT_DELAY = 380L   // ms held before auto-repeat starts
        private const val REPEAT_RATE = 65L     // ms between repeats
    }

    /** Clears all sticky modifiers (called after a key was consumed).
     *  Skipped while a repeatable key is being HELD: the modifiers apply to
     *  the whole hold, like keeping a real key pressed. */
    fun consumeToggles() {
        if (repeatJobs.isNotEmpty()) return
        if (!isCtrlActive && !isAltActive && !isShiftActive) return
        isCtrlActive = false
        isAltActive = false
        isShiftActive = false
        refreshToggles()
    }

    // ------------------------------------------------------------------ build

    private fun build(container: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun addKey(
            label: String,
            repeat: Boolean = false,
            onClick: (View) -> Unit
        ): TextView {
            val key = TextView(activity)
            key.text = label
            key.gravity = Gravity.CENTER
            key.textSize = 13f
            key.typeface = Typeface.DEFAULT_BOLD
            key.setTextColor(0xFFF2F2F2.toInt())
            key.background = ContextCompat.getDrawable(activity, R.drawable.ut_key_bg)
            if (repeat) {
                // press-and-hold auto-repeat: fire once, wait, then keep firing
                key.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performClick()
                            onClick(v)
                            val job = object : Runnable {
                                override fun run() {
                                    onClick(v)
                                    handler.postDelayed(this, REPEAT_RATE)
                                }
                            }
                            repeatJobs[v] = job
                            handler.postDelayed(job, REPEAT_DELAY)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (ev.x < 0 || ev.y < 0 || ev.x > v.width || ev.y > v.height)
                                stopRepeat(v)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stopRepeat(v)
                            true
                        }
                        else -> false
                    }
                }
            } else {
                key.setOnClickListener { v -> onClick(v) }
            }
            val lp = LinearLayout.LayoutParams(dp(48), dp(38)).apply {
                marginEnd = dp(3)
            }
            container.addView(key, lp)
            return key
        }

        addKey("☰") { onSessions() }
        mouseButton = addKey("🖱") { toggleMouse() }
        mouseButton?.background = ContextCompat.getDrawable(activity, R.drawable.ut_key_bg_active) // ON by default
        addKey("⋮") { v -> onMenu(v) }
        addKey("ESC") { onWrite("\u001B") }
        addKey("TAB", repeat = true) { onWrite("\t") }
        ctrlButton = addKey("CTRL") { toggleCtrl() }
        altButton = addKey("ALT") { toggleAlt() }
        shiftButton = addKey("SHIFT") { toggleShift() }
        addKey("←", repeat = true) { writeArrow("D") }
        addKey("↑", repeat = true) { writeArrow("A") }
        addKey("↓", repeat = true) { writeArrow("B") }
        addKey("→", repeat = true) { writeArrow("C") }
        addKey("HOME", repeat = true) { writeArrow("H") }
        addKey("END", repeat = true) { writeArrow("F") }
        addKey("PGUP", repeat = true) { writeTilde("5") }
        addKey("PGDN", repeat = true) { writeTilde("6") }
        addKey("F1") { writeSS3("P") }
        addKey("F2") { writeSS3("Q") }
        addKey("F3") { writeSS3("R") }
        addKey("F4") { writeSS3("S") }
        addKey("F5") { writeTilde("15") }
        addKey("F6") { writeTilde("17") }
        addKey("F7") { writeTilde("18") }
        addKey("F8") { writeTilde("19") }
        addKey("F9") { writeTilde("20") }
        addKey("F10") { writeTilde("21") }
        addKey("F11") { writeTilde("23") }
        addKey("F12") { writeTilde("24") }
        addKey("/", repeat = true) { onWrite("/") }
        addKey("-", repeat = true) { onWrite("-") }
        addKey("|", repeat = true) { onWrite("|") }
        addKey("~", repeat = true) { onWrite("~") }
    }

    private fun toggleMouse() {
        isMouseActive = !isMouseActive
        onToggleMouse(isMouseActive)
        mouseButton?.background = ContextCompat.getDrawable(
            activity, if (isMouseActive) R.drawable.ut_key_bg_active else R.drawable.ut_key_bg
        )
    }

    /** stops the auto-repeat of one held key; modifiers reset when none left */
    private fun stopRepeat(v: View) {
        repeatJobs.remove(v)?.let { handler.removeCallbacks(it) }
        if (repeatJobs.isEmpty()) consumeToggles()
    }

    /** modifier-aware navigation: plain, or CSI 1;mX with sticky modifiers */
    private fun writeArrow(c: String) {
        val m = mods()
        onWrite(if (m == 1) "\u001B[$c" else "\u001B[1;$m$c")
    }

    private fun writeTilde(n: String) {
        val m = mods()
        onWrite(if (m == 1) "\u001B[$n~" else "\u001B[$n;$m~")
    }

    /** F1-F4: plain SS3 (ESC O P..S) or CSI 1;mP with modifiers */
    private fun writeSS3(c: String) {
        val m = mods()
        onWrite(if (m == 1) "\u001BO$c" else "\u001B[1;$m$c")
    }

    private fun mods(): Int {
        var m = 1
        if (isShiftActive) m += 1
        if (isAltActive) m += 2
        if (isCtrlActive) m += 4
        return m
    }

    private fun toggleShift() {
        isShiftActive = !isShiftActive
        refreshToggles()
    }

    private fun toggleCtrl() {
        isCtrlActive = !isCtrlActive
        refreshToggles()
    }

    private fun toggleAlt() {
        isAltActive = !isAltActive
        refreshToggles()
    }

    private fun refreshToggles() {
        ctrlButton?.background = ContextCompat.getDrawable(
            activity, if (isCtrlActive) R.drawable.ut_key_bg_active else R.drawable.ut_key_bg
        )
        altButton?.background = ContextCompat.getDrawable(
            activity, if (isAltActive) R.drawable.ut_key_bg_active else R.drawable.ut_key_bg
        )
        shiftButton?.background = ContextCompat.getDrawable(
            activity, if (isShiftActive) R.drawable.ut_key_bg_active else R.drawable.ut_key_bg
        )
    }
}
