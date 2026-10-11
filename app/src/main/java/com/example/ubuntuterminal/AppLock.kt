package com.example.ubuntuterminal

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * App lock: fingerprint (the legacy FingerprintManager works fine on modern
 * Androids and needs no extra dependencies) with a PIN fallback. The lock
 * shows once per cold app start when enabled in the settings screen.
 */
object AppLock {

    @Volatile var unlocked = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ut_settings", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getString("lock_on", "0") == "1"

    /** Shows a non-cancelable lock overlay when the app starts locked. */
    fun maybeLock(activity: Activity) {
        if (unlocked || !isEnabled(activity)) return
        val pin = (prefs(activity).getString("lock_pin", "") ?: "").trim()
        val fm = runCatching {
            activity.getSystemService(Context.FINGERPRINT_SERVICE)
                as? android.hardware.fingerprint.FingerprintManager
        }.getOrNull()
        val bioOk = runCatching {
            fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
        }.getOrDefault(false)
        if (!bioOk && pin.isEmpty()) return   // nothing to authenticate with

        val theme = TerminalTheme.fromId(prefs(activity).getString("theme", "dark") ?: "dark")
        val accent = theme.ansi[2]
        val handler = Handler(Looper.getMainLooper())

        val dialog = Dialog(activity)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun t(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        fun unlock() {
            unlocked = true
            runCatching { dialog.dismiss() }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(30), dp(28), dp(26))
        }
        root.addView(t("🔒", 52f, accent))
        root.addView(t("التطبيق مقفول", 20f, theme.fg, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL }
        })
        val status = t("", 14f, theme.fg).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        root.addView(status)

        if (pin.isNotEmpty()) {
            val pinEdit = EditText(activity).apply {
                hint = "الرمز الاحتياطي"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setTextColor(theme.fg)
                setHintTextColor(Color.argb(0x99, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)))
                textSize = 15f
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.argb(0x33, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)))
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            root.addView(pinEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) })
            val btn = Button(activity).apply {
                text = "فتح"
                textSize = 16f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(accent)
                }
                setOnClickListener {
                    if (pinEdit.text.toString().trim() == pin) unlock()
                    else Toast.makeText(activity, "رمز خطأ — حاول تاني", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
        }

        status.text = when {
            bioOk && pin.isNotEmpty() -> "المس مستشعر البصمة أو أدخل الرمز"
            bioOk -> "المس مستشعر البصمة للفتح"
            else -> "أدخل رمز PIN"
        }

        dialog.setContentView(root)
        runCatching {
            dialog.window?.setBackgroundDrawable(
                GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(theme.bg) }
            )
        }

        if (bioOk) {
            runCatching {
                fm?.authenticate(null, CancellationSignal(), 0,
                    object : android.hardware.fingerprint.FingerprintManager.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.fingerprint.FingerprintManager.AuthenticationResult?) {
                            handler.post { unlock() }
                        }
                        override fun onAuthenticationFailed() {
                            handler.post {
                                Toast.makeText(activity, "بصمة غير معروفة — حاول تاني", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            handler.post {
                                status.text = "البصمة: $errString" +
                                    if (pin.isNotEmpty()) " — استخدم الرمز" else " — أعد المحاولة بعد لحظات"
                            }
                        }
                    }, handler)
            }.onFailure {
                status.text = "البصمة غير متاحة الآن" +
                    if (pin.isNotEmpty()) " — استخدم الرمز" else ""
            }
        }
        dialog.show()
    }
}
