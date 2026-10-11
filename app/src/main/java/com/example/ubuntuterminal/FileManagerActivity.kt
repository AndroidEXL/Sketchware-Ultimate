package com.example.ubuntuterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.ubuntuterminal.setup.Installer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Visual file manager for the Ubuntu filesystem: browse (with the proot
 * binds emulated), preview text/images, share files with any Android app
 * (via FileProvider), rename/delete/new-folder and download from a URL.
 */
class FileManagerActivity : Activity() {

    companion object {
        private val TEXT_EXT = setOf(
            "txt", "md", "sh", "conf", "json", "xml", "log", "py", "js", "ts", "kt", "java",
            "c", "cpp", "h", "hpp", "css", "html", "csv", "yml", "yaml", "ini", "env",
            "list", "sources", "cfg", "toml", "rb", "go", "rs", "prop", "properties"
        )
        private val IMG_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /** guest (Ubuntu) path -> real Android file, replicating the proot binds */
        fun guestToReal(app: Context, guest: String): File {
            val rootfs = Installer.rootfsDir(app)
            val home = Installer.homeDir(app)
            return when {
                guest == "/" -> rootfs
                guest == "/root" -> home
                guest.startsWith("/root/") -> File(home, guest.removePrefix("/root"))
                guest == "/sdcard" -> File("/storage/emulated/0")
                guest.startsWith("/sdcard/") -> File("/storage/emulated/0", guest.removePrefix("/sdcard"))
                guest.startsWith("/storage/emulated/") -> File(guest)
                else -> File(rootfs, guest.removePrefix("/"))
            }
        }

        private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "txt", "md", "log", "csv", "json", "xml", "yml", "yaml", "ini", "conf", "sh" -> "text/plain"
            "pdf" -> "application/pdf"
            "mp3", "wav", "ogg", "flac", "m4a" -> "audio/*"
            "mp4", "mkv", "avi", "webm", "mov" -> "video/*"
            else -> "*/*"
        }

        private fun iconOf(f: File): String {
            if (f.isDirectory) return "📁"
            return when (f.extension.lowercase()) {
                in IMG_EXT -> "🖼"
                "deb", "tar", "gz", "xz", "zip", "bz2", "7z", "rar", "tgz" -> "📦"
                "mp3", "wav", "ogg", "flac", "m4a" -> "🎵"
                "mp4", "mkv", "avi", "webm", "mov" -> "🎬"
                "pdf" -> "📕"
                "sh", "py", "js", "kt", "java", "c", "cpp", "h", "rs", "go", "rb" -> "💻"
                else -> "📄"
            }
        }
    }

    private val theme by lazy {
        TerminalTheme.fromId(getSharedPreferences("ut_settings", MODE_PRIVATE).getString("theme", "dark") ?: "dark")
    }
    private val accent by lazy { theme.ansi[2] }
    private val secondary by lazy { Color.argb(0x99, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)) }

    private val handler = Handler(Looper.getMainLooper())
    private var curPath = "/root"
    private lateinit var pathView: TextView
    private lateinit var listBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ ui

    private fun topBtn(icon: String, onClick: () -> Unit): Button = Button(this).apply {
        text = icon
        textSize = 17f
        setTextColor(theme.fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(0x33, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)))
        }
        layoutParams = LinearLayout.LayoutParams(dp(50), dp(48)).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(theme.bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        scroll.addView(root)
        setContentView(scroll)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(topBtn("🏠") { list("/root") })
        bar.addView(topBtn("⬆") { goUp() })
        bar.addView(topBtn("＋") { newFolder() })
        bar.addView(topBtn("⬇") { downloadDialog() })
        bar.addView(topBtn("📂") { jumpDialog() })
        root.addView(bar)

        pathView = TextView(this).apply {
            textSize = 13f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        root.addView(pathView)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        list(curPath)
    }

    private fun goUp() {
        if (curPath == "/") return
        val p = curPath.removeSuffix("/").substringBeforeLast("/")
        list(if (p.isBlank()) "/" else p)
    }

    // ------------------------------------------------------------------ listing

    private fun list(path: String) {
        curPath = path
        pathView.text = path
        listBox.removeAllViews()
        listBox.addView(rowLoading())
        thread {
            val dir = guestToReal(this, path)
            val files = runCatching {
                dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }.getOrNull()
            handler.post { render(files) }
        }
    }

    private fun rowLoading(): TextView = TextView(this).apply {
        text = "…"
        textSize = 14f
        setTextColor(secondary)
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    private fun render(files: List<File>?) {
        listBox.removeAllViews()
        if (files == null) {
            listBox.addView(simpleRow("⛔ لا يمكن قراءة هذا المجلد (صلاحية؟)"))
            return
        }
        if (files.isEmpty()) {
            listBox.addView(simpleRow("المجلد فاضي"))
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(0x1E, Color.red(theme.fg), Color.green(theme.fg), Color.blue(theme.fg)))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) }
            }
            val icon = TextView(this).apply {
                text = iconOf(f); textSize = 21f
                setPadding(dp(2), dp(0), dp(10), dp(0))
            }
            row.addView(icon)
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = f.name
                textSize = 15f
                setTextColor(theme.fg)
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(this).apply {
                text = if (f.isDirectory) "مجلد" else human(f.length()) + " — " + fmt.format(Date(f.lastModified()))
                textSize = 12f
                setTextColor(secondary)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                if (f.isDirectory) list(if (curPath == "/") "/${f.name}" else "$curPath/${f.name}")
                else fileActions(f)
            }
            row.setOnLongClickListener { fileActions(f); true }
            listBox.addView(row)
        }
    }

    private fun simpleRow(s: String): TextView = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(secondary)
        setPadding(dp(4), dp(12), dp(4), dp(12))
    }

    // ------------------------------------------------------------------ actions

    private fun refresh() = list(curPath)

    private fun fileActions(f: File) {
        val items = if (f.isDirectory)
            arrayOf("✏️ إعادة تسمية", "🗑 حذف", "ℹ️ خصائص")
        else
            arrayOf("👁 فتح", "📤 مشاركة", "✏️ إعادة تسمية", "🗑 حذف", "ℹ️ خصائص")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "👁 فتح" -> preview(f)
                    "📤 مشاركة" -> share(f)
                    "✏️ إعادة تسمية" -> renameDialog(f)
                    "🗑 حذف" -> deleteDialog(f)
                    "ℹ️ خصائص" -> propsDialog(f)
                }
            }
            .show()
    }

    private fun preview(f: File) {
        val ext = f.extension.lowercase()
        when {
            IMG_EXT.contains(ext) -> previewImage(f)
            TEXT_EXT.contains(ext) -> previewText(f)
            else -> Toast.makeText(this, "لا يمكن عرض هذا النوع — شاركه أو افتحه من التيرمنال", Toast.LENGTH_SHORT).show()
        }
    }

    private fun previewImage(f: File) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalArgumentException("صورة غير صالحة")
            val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(iv)
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("📤 مشاركة") { _, _ -> share(f) }
                .show()
        }.onFailure {
            Toast.makeText(this, "تعذر فتح الصورة: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun previewText(f: File) {
        if (f.length() > 2 * 1024 * 1024) {
            Toast.makeText(this, "الملف كبير (>2MB) — افتحه من التيرمنال", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val content = f.readText(Charsets.UTF_8).take(128 * 1024)
            val tv = TextView(this).apply {
                text = content
                textSize = 12f
                setTextColor(theme.fg)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            val sv = ScrollView(this).apply { setBackgroundColor(theme.bg); addView(tv) }
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(sv)
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("📤 مشاركة") { _, _ -> share(f) }
                .show()
        }.onFailure {
            Toast.makeText(this, "تعذر قراءة الملف: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.ut.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(f)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "مشاركة ${f.name}"))
        }.onFailure {
            Toast.makeText(this, "فشلت المشاركة: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameDialog(f: File) {
        val edit = EditText(this).apply { setText(f.name); setTextColor(theme.fg) }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(edit)
            .setPositiveButton("موافق") { _, _ ->
                val nn = edit.text.toString().trim()
                if (nn.isNotEmpty() && nn != f.name) {
                    thread {
                        val ok = runCatching { f.renameTo(File(f.parentFile, nn)) }.getOrDefault(false)
                        handler.post {
                            if (ok) refresh()
                            else Toast.makeText(this, "فشلت إعادة التسمية", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun deleteDialog(f: File) {
        AlertDialog.Builder(this)
            .setTitle("حذف ${f.name}؟")
            .setMessage(if (f.isDirectory) "سيُحذف المجلد وكل محتوياته!" else "لا يمكن التراجع عن الحذف.")
            .setPositiveButton("🗑 حذف") { _, _ ->
                thread {
                    fun rec(x: File) {
                        if (x.isDirectory) x.listFiles()?.forEach { rec(it) }
                        x.delete()
                    }
                    runCatching { rec(f) }
                    handler.post { refresh() }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun propsDialog(f: File) {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        val msg = "المسار (أوبونتو): $curPath/${f.name}\n" +
            "الحجم: ${if (f.isDirectory) "مجلد" else human(f.length())}\n" +
            "آخر تعديل: ${fmt.format(Date(f.lastModified()))}\n" +
            "الصلاحيات: ${if (f.canRead()) "قراءة " else ""}${if (f.canWrite()) "كتابة " else ""}${if (f.canExecute()) "تنفيذ" else ""}"
        AlertDialog.Builder(this).setTitle("ℹ️ ${f.name}").setMessage(msg)
            .setPositiveButton("إغلاق", null).show()
    }

    private fun newFolder() {
        val edit = EditText(this).apply { hint = "اسم المجلد الجديد"; setTextColor(theme.fg) }
        AlertDialog.Builder(this)
            .setTitle("مجلد جديد في $curPath")
            .setView(edit)
            .setPositiveButton("إنشاء") { _, _ ->
                val nn = edit.text.toString().trim()
                if (nn.isNotEmpty()) {
                    thread {
                        val ok = runCatching { File(guestToReal(this@FileManagerActivity, curPath), nn).mkdirs() }
                            .getOrDefault(false)
                        handler.post {
                            if (ok) refresh()
                            else Toast.makeText(this, "فشل الإنشاء", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun jumpDialog() {
        val edit = EditText(this).apply { setText(curPath); setTextColor(theme.fg) }
        AlertDialog.Builder(this)
            .setTitle("اذهب إلى مسار")
            .setMessage("أمثلة: /root ، /etc ، /sdcard/Download ، /")
            .setView(edit)
            .setPositiveButton("اذهب") { _, _ ->
                var p = edit.text.toString().trim().replace(Regex("/+"), "/")
                if (!p.startsWith("/")) p = "/$p"
                if (p.length > 1) p = p.removeSuffix("/")
                if (p.contains("..")) {
                    Toast.makeText(this, "غير مسموح بـ ..", Toast.LENGTH_SHORT).show()
                } else if (guestToReal(this, p).isDirectory) {
                    list(p)
                } else {
                    Toast.makeText(this, "المسار غير موجود", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun downloadDialog() {
        val edit = EditText(this).apply {
            hint = "https://example.com/file.zip"
            setTextColor(theme.fg)
            textSize = 13f
        }
        AlertDialog.Builder(this)
            .setTitle("⬇ تنزيل ملف إلى $curPath")
            .setView(edit)
            .setPositiveButton("تنزيل") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Toast.makeText(this, "بدأ التنزيل في الخلفية — ه يوصلك إشعار", Toast.LENGTH_SHORT).show()
                    thread { download(url) }
                } else {
                    Toast.makeText(this, "الرابط لازم يبدأ بـ http أو https", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun download(url: String) {
        val app = applicationContext
        val result = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            try {
                if (conn.responseCode !in 200..299)
                    throw java.io.IOException("HTTP ${conn.responseCode}")
                var name = url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
                if (name.length > 100) name = name.take(100)
                val out = File(guestToReal(app, curPath), name)
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                "OK ${out.name} — ${human(out.length())}"
            } finally {
                conn.disconnect()
            }
        }.getOrElse { "فشل: ${it.message ?: it.javaClass.simpleName}" }
        val ok = result.startsWith("OK")
        AndroidApiBridge.notifyUser(
            app,
            if (ok) "⬇️ اكتمل التنزيل" else "❌ فشل التنزيل",
            if (ok) result.removePrefix("OK ").trim() + "\nفي: $curPath" else result
        )
        handler.post { refresh() }
    }

    private fun human(b: Long): String {
        val v = b.toDouble()
        return when {
            b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", v / (1L shl 30))
            b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", v / (1L shl 20))
            b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", v / (1L shl 10))
            else -> "$b B"
        }
    }
}
