package com.example.ubuntuterminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference

/**
 * Bridges the terminal session (process) back to the activity.
 * All callbacks arrive on the main thread (guaranteed by TerminalSession).
 */
class TermSessionClient(activity: MainActivity) : TerminalSessionClient {

    private val activityRef = WeakReference(activity)

    private inline fun withActivity(block: MainActivity.() -> Unit) {
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        block(activity)
    }

    override fun onTextChanged(changedSession: TerminalSession) =
        withActivity { onTerminalTextChanged(changedSession) }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) =
        withActivity { onTerminalSessionFinished(finishedSession) }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) =
        withActivity { copyToClipboard(text) }

    override fun onPasteTextFromClipboard(session: TerminalSession) =
        withActivity { pasteIntoSession(session) }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) =
        withActivity { onTerminalTextChanged(session) }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    /** null = let the emulator decide (block cursor). */
    override fun getTerminalCursorStyle(): Int? = null

    // ---------------------------------------------------------------- logging

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}
