package com.example.ubuntuterminal

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/**
 * Bridges the terminal *view* (rendering & input) to the activity:
 * pinch-zoom font scaling, soft keyboard handling, the sticky CTRL/ALT
 * extra keys and long-press text selection.
 */
class TermViewClient(private val activity: MainActivity) : TerminalViewClient {

    // ------------------------------------------------------------- gestures

    override fun onScale(scale: Float): Float {
        val newSize = activity.terminalFontSize + if (scale > 1f) 1 else -1
        if (newSize in 6..40 && newSize != activity.terminalFontSize) {
            activity.terminalFontSize = newSize
            activity.terminalView.setTextSize(newSize)
            activity.saveSetting("font", newSize.toString())
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = activity.terminalView
        if (view.isSelectingText) {
            view.stopTextSelectionMode()
            return
        }
        view.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        // start the built-in text selection (copy/paste handles), like Termux
        activity.terminalView.startTextSelectionMode(event)
        return true
    }

    // ------------------------------------------------------------- options

    override fun shouldBackButtonBeMappedToEscape() = false

    /** Makes soft keyboards send characters one by one (no autocompletion). */
    override fun shouldEnforceCharBasedInput() = true

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = activity.terminalView.hasFocus()

    override fun copyModeChanged(copyMode: Boolean) = Unit

    // ------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        activity.completion.onKey(keyCode, e)
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        // one-shot behaviour for the CTRL/ALT extra keys (hardware keyboard path)
        if (!KeyEvent.isModifierKey(keyCode)) activity.extraKeys.consumeToggles()
        return false
    }

    override fun readControlKey(): Boolean = activity.extraKeys.isCtrlActive
    override fun readAltKey(): Boolean = activity.extraKeys.isAltActive
    override fun readShiftKey(): Boolean = activity.extraKeys.isShiftActive
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // extra-keys SHIFT acts like a keyboard shift: lowercase -> UPPERCASE
        if (activity.extraKeys.isShiftActive && codePoint in 'a'.code..'z'.code) {
            val up = codePoint - 32
            activity.completion.onChar(up, ctrlDown)
            activity.extraKeys.consumeToggles()
            TerminalManager.activeSession?.write(up.toChar().toString())
            return true
        }
        // feed the completion bar first (alt state must still be visible)
        activity.completion.onChar(codePoint, ctrlDown)
        // one-shot behaviour for the CTRL/ALT/SHIFT extra keys (IME path)
        activity.extraKeys.consumeToggles()
        return false
    }

    override fun onEmulatorSet() {
        activity.terminalView.setTextSize(activity.terminalFontSize)
    }

    // ------------------------------------------------------------- logging

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}
