package com.example.ubuntuterminal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * In-app settings: terminal color theme, font size and keep-screen-on.
 * Everything is written to the "settings" preferences and applied by
 * MainActivity.onResume() when the user goes back to the terminal.
 */
class SettingsActivity : Activity() {

    private val cards = HashMap<String, LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("⚙ الإعدادات", 22f, "#E95420", topPad = 0, bottomPad = 4))
        root.addView(label("التغييرات تُطبَّق فور الرجوع للترمنال", 13f, "#9E9E9E"))

        // ---------------------------------------------------------- theme
        root.addView(label("🎨 المظهر — Theme", 15f, "#FFFFFF", top = 22))
        for (t in TerminalTheme.THEMES) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
            }
            val name = TextView(this).apply {
                text = t.name
                textSize = 15f
                setTextColor(t.fg)
                setTypeface(typeface, Typeface.BOLD)
            }
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            for (col in listOf(t.bg, t.fg) + t.ansi.toList()) {
                strip.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(19), dp(19)).apply {
                        marginEnd = dp(2)
                    }
                    setBackgroundColor(col)
                })
            }
            card.addView(name)
            card.addView(strip)
            card.setOnClickListener {
                saveSetting("theme", t.id)
                refreshBorders()
                Toast.makeText(this, "تم اختيار ${t.name} ✓", Toast.LENGTH_SHORT).show()
            }
            cards[t.id] = card
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        refreshBorders()

        // ---------------------------------------------------------- font
        root.addView(label("🔤 حجم الخط", 15f, "#FFFFFF", top = 26))
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sizeView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = readSetting("font", "14")
        }
        fun fontStep(d: Int) {
            val n = ((readSetting("font", "14").toIntOrNull() ?: 14) + d).coerceIn(6, 40)
            saveSetting("font", n.toString())
            sizeView.text = n.toString()
        }
        fontRow.addView(btn("−") { fontStep(-1) })
        fontRow.addView(sizeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fontRow.addView(btn("+") { fontStep(+1) })
        root.addView(fontRow)

        // ---------------------------------------------------------- keep screen on
        root.addView(label("🔆 منع انطفاء الشاشة والتطبيق مفتوح", 15f, "#FFFFFF", top = 26))
        val sw = Switch(this).apply {
            isChecked = readSetting("keepon", "0") == "1"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                saveSetting("keepon", if (checked) "1" else "0")
            }
        }
        root.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // -------------------------------------------------- command-done notify
        root.addView(label("🔔 إشعار انتهاء الأوامر الطويلة (30 ثانية+)", 15f, "#FFFFFF", top = 26))
        val nsw = Switch(this).apply {
            isChecked = readSetting("cmd_notify", "1") == "1"
            text = "إشعار على الموبايل + رسالة تليجرام عند انتهاء أمر طويل"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                saveSetting("cmd_notify", if (checked) "1" else "0")
            }
        }
        root.addView(nsw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ---------------------------------------------- package-install notify (v1.30)
        root.addView(label("📦 إشعارات تثبيت الحزم", 15f, "#FFFFFF", top = 26))
        val psw = Switch(this).apply {
            isChecked = readSetting("pkg_notify", "0") == "1"
            text = "إشعار «⏳ جاري تثبيت الحزمة» عند تثبيت أدوات الفورمات تلقائياً (apt) — مطفي افتراضياً"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                saveSetting("pkg_notify", if (checked) "1" else "0")
            }
        }
        root.addView(psw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        fun field(key: String, default: String, hint: String): EditText = EditText(this).apply {
            setText(readSetting(key, default))
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7A7A7A"))
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1A1A1A"))
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { saveSetting(key, s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(label("توكن بوت تليجرام (لرسائل انتهاء الأوامر)", 12f, "#9E9E9E", top = 12))
        root.addView(field("tg_token", AndroidApiBridge.DEFAULT_TG_TOKEN, "123456:ABC-DEF..."), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(label("معرّف الشات (chat_id)", 12f, "#9E9E9E", top = 10))
        root.addView(field("tg_chat", AndroidApiBridge.DEFAULT_TG_CHAT, "8627617658"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(label("شغّل البوت الأول بـ /start من تليجرام عشان يقدر يبعتلك", 11f, "#7A7A7A", top = 8))

        // -------------------------------------------------- app lock
        root.addView(label("🔐 قفل التطبيق بالبصمة", 15f, "#FFFFFF", top = 26))
        val lsw = Switch(this).apply {
            isChecked = readSetting("lock_on", "0") == "1"
            text = "طلب الفتح (بصمة أو رمز) عند تشغيل التطبيق"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                saveSetting("lock_on", if (checked) "1" else "0")
            }
        }
        root.addView(lsw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        root.addView(label("رمز PIN الاحتياطي (لو البصمة فشلت أو غير متاحة)", 12f, "#9E9E9E", top = 12))
        root.addView(field("lock_pin", "", "1234"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(label("مهم: لو نسيت الرمز وعندك بصمة مسجلة مش مشكلة — لو نسيته وملوش بصمة هتحتاج مسح بيانات التطبيق", 11f, "#7A7A7A", top = 8))

        root.addView(label("💡 لا تنس: dapi keepalive on يحمي البوت من النوم العميق", 12f, "#7A7A7A", top = 30))
    }

    // ------------------------------------------------------------------ helpers

    private fun refreshBorders() {
        val sel = readSetting("theme", "dark")
        for (t in TerminalTheme.THEMES) {
            val selected = t.id == sel
            cards[t.id]?.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (selected) Color.parseColor("#2A2A2A") else Color.parseColor("#1A1A1A"))
                setStroke(
                    if (selected) dp(2) else dp(1),
                    if (selected) Color.parseColor("#E95420") else Color.parseColor("#333333")
                )
            }
        }
    }

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#2A2A2A"))
        }
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(52)).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun label(
        text: String, size: Float, color: String, top: Int = 0, topPad: Int = 0, bottomPad: Int = 8
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(topPad), 0, dp(bottomPad))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun readSetting(key: String, default: String): String =
        getSharedPreferences("ut_settings", MODE_PRIVATE).getString(key, default) ?: default

    private fun saveSetting(key: String, value: String) {
        getSharedPreferences("ut_settings", MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
