package com.example.ubuntuterminal

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.ubuntuterminal.setup.Installer
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Live resource monitor: CPU (overall + per core), RAM, storage,
 * battery, network and system info — refreshed every 1.5s.
 * Reads /proc/stat, /sys cpufreq, ActivityManager, StatFs and the
 * battery sticky broadcast; no permissions required.
 */
class MonitorActivity : Activity() {

    private val theme by lazy {
        TerminalTheme.fromId(getSharedPreferences("ut_settings", MODE_PRIVATE).getString("theme", "dark") ?: "dark")
    }
    private val accent by lazy { theme.ansi[2] }
    private val secondary by lazy { Color.argb(0x99, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)) }
    private val cardBg by lazy { Color.argb(0x22, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)) }
    private val trackBg by lazy { Color.argb(0x33, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)) }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            runCatching { refresh() }
            if (running) handler.postDelayed(this, 1500)
        }
    }

    // ---- CPU state --------------------------------------------------------
    private var prevCpu: List<LongArray>? = null
    private var cpuModeChecked = false
    private var systemCpuMode = true      // false = /proc/stat blocked → per-UID fallback
    private var prevProcTotal = -1L
    private var prevProcMap = HashMap<Int, Pair<String, Long>>()
    private var prevProcMs = 0L
    private lateinit var cpuNote: TextView
    private val coreBars = ArrayList<Pair<ProgressBar, TextView>>()
    private lateinit var coreRows: LinearLayout
    private lateinit var cpuBar: ProgressBar
    private lateinit var cpuPct: TextView
    private lateinit var cpuDetail: TextView

    // ---- RAM ---------------------------------------------------------------
    private lateinit var ramBar: ProgressBar
    private lateinit var ramPct: TextView
    private lateinit var ramDetail: TextView

    // ---- Storage ------------------------------------------------------------
    private lateinit var phoneBar: ProgressBar
    private lateinit var phoneDetail: TextView
    private lateinit var dataBar: ProgressBar
    private lateinit var dataDetail: TextView
    private lateinit var rootfsDetail: TextView

    // ---- Battery ------------------------------------------------------------
    private lateinit var batBar: ProgressBar
    private lateinit var batPct: TextView
    private lateinit var batDetail: TextView

    // ---- Network ------------------------------------------------------------
    private lateinit var netDetail: TextView
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastNetMs = 0L

    // ---- System --------------------------------------------------------------
    private lateinit var sysDetail: TextView

    // ------------------------------------------------------------------ ui

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun bar(heightDp: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(accent)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(trackBg)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
                .apply { topMargin = dp(6) }
        }

    private fun card(root: LinearLayout, title: String): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
            }
        }
        root.addView(
            inner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        )
        inner.addView(text(title, 16f, theme.fg, bold = true))
        return inner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(theme.bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("📊 مراقبة الموارد", 22f, accent, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(text("تحديث تلقائي كل 1.5 ثانية — أرقام جهازك الحقيقية", 12f, secondary).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2) }
        })

        // ---------------- CPU card
        val cpu = card(root, "🧠 المعالج (CPU)")
        cpuNote = text("", 11f, secondary)
        cpu.addView(cpuNote)
        val cpuTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        }
        cpuPct = text("…", 30f, accent, bold = true)
        cpuTop.addView(cpuPct, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
        val spacer = android.view.View(this)
        cpuTop.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        cpuDetail = text("", 12f, secondary)
        cpuTop.addView(cpuDetail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
        cpu.addView(cpuTop)
        cpuBar = bar(12)
        cpu.addView(cpuBar)
        coreRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cpu.addView(coreRows)

        // ---------------- RAM card
        val ram = card(root, "📦 الذاكرة (RAM)")
        ramPct = text("…", 30f, accent, bold = true)
        ram.addView(ramPct)
        ramBar = bar(12)
        ram.addView(ramBar)
        ramDetail = text("", 12f, secondary)
        ram.addView(ramDetail)

        // ---------------- Storage card
        val st = card(root, "💾 التخزين")
        st.addView(text("تخزين الهاتف (المشترك)", 13f, theme.fg, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        })
        phoneBar = bar(10)
        st.addView(phoneBar)
        phoneDetail = text("", 12f, secondary)
        st.addView(phoneDetail)
        st.addView(text("قسم النظام /data (التطبيقات + أوبونتو)", 13f, theme.fg, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })
        dataBar = bar(10)
        st.addView(dataBar)
        dataDetail = text("", 12f, secondary)
        st.addView(dataDetail)
        rootfsDetail = text("حجم أوبونتو (rootfs): يُحسب…", 12f, secondary)
        rootfsDetail.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
        st.addView(rootfsDetail)

        // ---------------- Battery card
        val bat = card(root, "🔋 البطارية")
        batPct = text("…", 30f, accent, bold = true)
        bat.addView(batPct)
        batBar = bar(12)
        bat.addView(batBar)
        batDetail = text("", 12f, secondary)
        bat.addView(batDetail)

        // ---------------- Network card
        val net = card(root, "🌐 الشبكة")
        netDetail = text("…", 14f, theme.fg)
        net.addView(netDetail)

        // ---------------- System card
        val sys = card(root, "ℹ️ النظام")
        sysDetail = text("", 13f, theme.fg)
        sys.addView(sysDetail)

        // initial samples so the first tick already has a delta baseline
        prevCpu = readCpu()
        readProcSample()?.let { prevProcTotal = it.first; prevProcMap = it.second }
        prevProcMs = SystemClock.elapsedRealtime()
        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()
        lastNetMs = SystemClock.elapsedRealtime()

        // one-time background walk of the Ubuntu rootfs size
        thread(priority = Thread.MIN_PRIORITY) {
            runCatching {
                val f = Installer.rootfsDir(this@MonitorActivity)
                var bytes = 0L
                var files = 0L
                if (f.exists()) f.walkTopDown().forEach {
                    if (it.isFile) { bytes += it.length(); files++ }
                }
                handler.post {
                    runCatching { rootfsDetail.text = "حجم أوبونتو (rootfs): ${human(bytes)} — ${files} ملف" }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!running) { running = true; handler.post(tick) }
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(tick)
    }

    // ------------------------------------------------------------- data

    /** returns one [total, idle] per cpu line (overall first, then cores) */
    private fun readCpu(): List<LongArray>? = runCatching {
        File("/proc/stat").readLines().filter { it.startsWith("cpu") }.map { l ->
            val vals = l.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            val idle = (vals.getOrNull(3) ?: 0L) + (vals.getOrNull(4) ?: 0L)
            longArrayOf(vals.sum(), idle)
        }
    }.getOrNull()

    private fun readFreqs(cores: Int): Long {
        var sum = 0L; var n = 0
        for (i in 0 until cores) {
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    .readText().trim().toLongOrNull()?.let { sum += it; n++ }
            }
        }
        return if (n > 0) sum / n else -1L
    }

    /**
     * Reads /proc/<pid>/stat for every process of OUR uid — the app itself
     * plus the whole Ubuntu container (proot and everything under it).
     * Returns (sum jiffies, pid -> (comm, jiffies)). CLK_TCK = 100.
     */
    private fun readProcSample(): Pair<Long, HashMap<Int, Pair<String, Long>>>? = runCatching {
        val names = File("/proc").list() ?: return@runCatching null
        val map = HashMap<Int, Pair<String, Long>>()
        var total = 0L
        for (n in names) {
            val pid = n.toIntOrNull() ?: continue
            val stat = runCatching { File("/proc/$n/stat").readText() }.getOrNull() ?: continue
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close <= open) continue
            val comm = stat.substring(open + 1, close)
            val rest = stat.substring(close + 2).split(' ')
            val ut = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val st = rest.getOrNull(12)?.toLongOrNull() ?: continue
            val j = ut + st
            total += j
            map[pid] = comm to j
        }
        total to map
    }.getOrNull()

    private fun refresh() {
        // ---- CPU.  /proc/stat is blocked for apps on Android 9+ (SELinux),
        // so when it is unreadable we fall back to reading OUR OWN processes
        // (/proc/<pid>/stat): the app plus the whole Ubuntu container.
        val cur = readCpu()
        if (!cpuModeChecked) { cpuModeChecked = true; systemCpuMode = cur != null && cur.size >= 2 }
        if (systemCpuMode) {
            cpuNote.text = ""
            if (cur != null && cur.size >= 2) {
                val prev = prevCpu
                if (prev != null && prev.size == cur.size) {
                    fun pct(c: LongArray, p: LongArray): Int {
                        val dT = c[0] - p[0]; val dI = c[1] - p[1]
                        return if (dT > 0) ((100L * (dT - dI)) / dT).toInt().coerceIn(0, 100) else 0
                    }
                    cpuPct.text = "${pct(cur[0], prev[0])}%"
                    cpuBar.progress = pct(cur[0], prev[0])
                    val cores = cur.size - 1
                    val perCore = (1..cores).map { pct(cur[it], prev[it]) }
                    ensureCoreRows(cores)
                    for (i in 0 until cores) {
                        coreBars[i].first.progress = perCore[i]
                        coreBars[i].second.text = "${perCore[i]}%"
                    }
                    val freq = readFreqs(cores)
                    val freqS = if (freq > 0) " — ⚡ ${String.format(Locale.US, "%.2f", freq / 1e6)} GHz" else ""
                    cpuDetail.text = "$cores أنوية$freqS"
                }
                prevCpu = cur
            }
        } else {
            cpuNote.text = "أندرويد يحجب قراءة النظام كله (أندرويد 9+) — المعروض استهلاك أوبونتو والتطبيق"
            val now = SystemClock.elapsedRealtime()
            val sample = readProcSample()
            if (sample != null && prevProcTotal >= 0 && now > prevProcMs && prevProcMap.isNotEmpty()) {
                val secs = ((now - prevProcMs) / 1000.0).coerceAtLeast(0.05)
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val dTotal = sample.first - prevProcTotal
                val p = ((dTotal / (100.0 * secs)) * 100.0 / cores).toInt().coerceIn(0, 100)
                cpuPct.text = "$p%"
                cpuBar.progress = p
                // top Ubuntu/app processes by CPU
                val tops = ArrayList<Pair<String, Int>>()
                for ((pid, pc) in prevProcMap) {
                    val cj = sample.second[pid]?.second ?: continue
                    val d = cj - pc.second
                    if (d > 0) tops.add(pc.first to ((d / (100.0 * secs)) * 100).toInt().coerceAtMost(999))
                }
                tops.sortByDescending { it.second }
                coreRows.removeAllViews(); coreBars.clear()
                var shown = 0
                for ((name, pp) in tops) {
                    if (shown >= 3 || pp < 1) break
                    coreRows.addView(text("• $name — $pp%", 12f, theme.fg).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                    })
                    shown++
                }
                if (shown == 0) coreRows.addView(text("لا استهلاك حالياً — التطبيق وأوبونتو نائمين 😴", 11f, secondary))
                val freq = readFreqs(cores)
                val freqS = if (freq > 0) " — ⚡ ${String.format(Locale.US, "%.2f", freq / 1e6)} GHz" else ""
                cpuDetail.text = "أنوية: $cores$freqS"
            }
            if (sample != null) { prevProcTotal = sample.first; prevProcMap = sample.second; prevProcMs = now }
        }

        // ---- RAM
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val used = mi.totalMem - mi.availMem
            val p = if (mi.totalMem > 0) ((100L * used) / mi.totalMem).toInt() else 0
            ramPct.text = "$p%"
            ramBar.progress = p
            ramDetail.text = "مستخدم ${human(used)} من ${human(mi.totalMem)} — متاح ${human(mi.availMem)}"
        }

        // ---- Storage
        runCatching {
            statInfo(Environment.getExternalStorageDirectory())?.let { (used, total, p) ->
                phoneBar.progress = p
                phoneDetail.text = "مستخدم ${human(used)} من ${human(total)}"
            } ?: run { phoneBar.progress = 0; phoneDetail.text = "غير متاح" }
            statInfo(Environment.getDataDirectory())?.let { (used, total, p) ->
                dataBar.progress = p
                dataDetail.text = "مستخدم ${human(used)} من ${human(total)}"
            } ?: run { dataBar.progress = 0; dataDetail.text = "غير متاح" }
        }

        // ---- Battery (sticky broadcast — no permission needed)
        runCatching {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val volt = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val p = level * 100 / scale
                batPct.text = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "🔌 $p%"
                    BatteryManager.BATTERY_STATUS_FULL -> "✅ $p%"
                    else -> "$p%"
                }
                batBar.progress = p
                val sb = StringBuilder()
                if (temp >= 0) sb.append("🌡 ${temp / 10.0}°C")
                if (volt > 0) { if (sb.isNotEmpty()) sb.append(" — "); sb.append("⚡ ${volt}mV") }
                batDetail.text = sb.toString()
            }
        }

        // ---- Network
        runCatching {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val now = SystemClock.elapsedRealtime()
            if (lastRx >= 0 && rx >= 0 && tx >= 0 && now > lastNetMs && lastNetMs > 0) {
                val secs = (now - lastNetMs) / 1000.0
                val dSpd = (rx - lastRx) / secs
                val uSpd = (tx - lastTx) / secs
                netDetail.text = "⬇ التنزيل: ${human(dSpd.toLong())}/s   (الإجمالي ${human(rx)})\n" +
                    "⬆ الرفع: ${human(uSpd.toLong())}/s   (الإجمالي ${human(tx)})"
            } else {
                netDetail.text = "⬇ الإجمالي: ${human(if (rx >= 0) rx else 0)}\n⬆ الإجمالي: ${human(if (tx >= 0) tx else 0)}"
            }
            lastRx = rx; lastTx = tx; lastNetMs = now
        }

        // ---- System
        runCatching {
            val ms = SystemClock.elapsedRealtime()
            val d = ms / 86400000; val h = (ms % 86400000) / 3600000; val m = (ms % 3600000) / 60000
            sysDetail.text = "📱 ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "🤖 أندرويد ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "🧵 ${Runtime.getRuntime().availableProcessors()} نواة معالج\n" +
                "⏱ مدة تشغيل الهاتف: ${d}ي ${h}س ${m}د"
        }
    }

    private fun ensureCoreRows(n: Int) {
        if (coreBars.size == n) return
        coreRows.removeAllViews()
        coreBars.clear()
        for (i in 0 until n) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(3) }
            }
            val name = text("ن${i + 1}", 11f, secondary)
            row.addView(name, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
            val b = bar(8)
            b.layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
            row.addView(b)
            val pctT = text("", 11f, theme.fg)
            row.addView(pctT, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            coreRows.addView(row)
            coreBars.add(b to pctT)
        }
    }

    /** returns (used, total, pct) or null */
    private fun statInfo(dir: File?): Triple<Long, Long, Int>? = runCatching {
        if (dir == null || !dir.exists()) return@runCatching null
        val st = StatFs(dir.absolutePath)
        val total = st.totalBytes
        val avail = st.availableBytes
        val used = total - avail
        val p = if (total > 0) ((100L * used) / total).toInt() else 0
        Triple(used, total, p)
    }.getOrNull()

    private fun human(b: Long): String {
        val v = b.toDouble()
        return when {
            b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", v / (1L shl 30))
            b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", v / (1L shl 20))
            b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", v / (1L shl 10))
            else -> "$b B"
        }
    }
}
