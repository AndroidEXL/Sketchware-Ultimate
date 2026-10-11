package com.example.ubuntuterminal

import pro.sketchware.R

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ubuntuterminal.setup.Installer
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var installJob: Job? = null

    // ---- setup pane ----
    private lateinit var setupPane: ScrollView
    private lateinit var btnInstall: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView

    // ---- terminal pane ----
    private lateinit var terminalPane: View
    lateinit var terminalView: TerminalView
        private set
    lateinit var extraKeys: ExtraKeysRow
        private set

    // ---- sessions drawer ----
    private lateinit var sessionDim: View
    private lateinit var sessionsDrawer: View
    private lateinit var sessionsList: LinearLayout

    var terminalFontSize = 14
    lateinit var completion: CompletionEngine
    private var sessionClient: TermSessionClient? = null

    companion object {
        private const val REQ_STORAGE = 100
        private val STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ut_activity_main)

        // Android 13+: make sure notification permission is actually granted
        // (needed by the command-finished alerts)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4701)
        }

        // app lock (fingerprint + PIN) when enabled in settings
        AppLock.maybeLock(this)

        setupPane = findViewById(R.id.setupPane)
        btnInstall = findViewById(R.id.btnInstall)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvStatus = findViewById(R.id.tvStatus)
        tvError = findViewById(R.id.tvError)
        terminalPane = findViewById(R.id.terminalPane)
        terminalView = findViewById(R.id.terminalView)

        sessionDim = findViewById(R.id.sessionDim)
        sessionsDrawer = findViewById(R.id.sessionsDrawer)
        sessionsList = findViewById(R.id.sessionsList)
        sessionDim.setOnClickListener { closeSessionsDrawer() }
        findViewById<Button>(R.id.btnNewSession).setOnClickListener { newSession() }

        completion = CompletionEngine(this)

        extraKeys = ExtraKeysRow(
            activity = this,
            container = findViewById(R.id.extraKeys),
            onWrite = { text ->
                TerminalManager.activeSession?.write(text)
                completion.onRawWrite(text)
                extraKeys.consumeToggles()
            },
            onMenu = { anchor -> showTerminalMenu(anchor) },
            onSessions = { openSessionsDrawer() },
            onToggleMouse = { on ->
                terminalView.mTouchMouseEnabled = on
                Toast.makeText(
                    this,
                    if (on) R.string.ut_mouse_mode_on else R.string.ut_mouse_mode_off,
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        btnInstall.setOnClickListener { startInstall() }

        when {
            Installer.isInstalled(this) -> showTerminal()
            // v1.29 auto-heal: the rootfs obviously exists but the marker
            // file is gone. Heal it - the setup screen would offer a
            // reinstall that silently wipes everything.
            Installer.looksInstalled(this) -> {
                Installer.healMarker(this)
                Toast.makeText(this,
                    "✅ تم إصلاح علامة التثبيت تلقائياً — كل بياناتك سليمة",
                    Toast.LENGTH_LONG).show()
                showTerminal()
            }
            else -> showSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        if (terminalPane.visibility == View.VISIBLE) {
            if (readSetting("keepon", "0") == "1")
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyCurrentTheme()
        }
        if (terminalPane.visibility == View.VISIBLE && sessionsDrawer.visibility == View.GONE) {
            terminalView.requestFocus()
        }
    }

    // ---------------------------------------------------------------- setup UI

    private fun showSetup() {
        terminalPane.visibility = View.GONE
        setupPane.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBusyUi(false)
    }

    private fun setBusyUi(busy: Boolean) {
        btnInstall.isEnabled = !busy
        btnInstall.text = getString(if (busy) R.string.ut_stage_downloading else R.string.ut_btn_install)
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        tvProgress.visibility = if (busy) View.VISIBLE else View.GONE
        tvProgress.text = ""
        tvStatus.visibility = if (busy) View.VISIBLE else View.GONE
        tvError.visibility = View.GONE
    }

    private fun startInstall() {
        if (installJob?.isActive == true) return

        // v1.29: installing over an existing rootfs erases EVERYTHING the
        // user did (programs, files, settings). Confirm first and take an
        // automatic backup - no more silent wipes.
        if (Installer.looksInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ فيه نظام أوبونتو موجود بالفعل!")
                .setMessage("إعادة التثبيت هتمسح كل حاجة عملتها (البرامج والملفات والإعدادات).\n\n" +
                    "قبل المسح هياخد التطبيق نسخة احتياطية تلقائية في مجلد UbuntuTerminal من:\n" +
                    "• ملفاتك (home) لحد 500 ميجا\n" +
                    "• قايمة البرامج المثبتة (apt) وإعداداته\n\n" +
                    "متأكد إنك عايز تمسح وتثبّت من جديد؟")
                .setPositiveButton("امسح وثبّت من جديد") { _, _ -> backupThenReinstall() }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        realStartInstall()
    }

    /**
     * v1.29: backs up the user's data (worker thread), then continues with
     * the destructive reinstall. Aborts - wiping nothing - when the backup
     * directory cannot even be created (no storage permission).
     */
    private fun backupThenReinstall() {
        setBusyUi(true)
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "⏳ جاري أخذ نسخة احتياطية قبل المسح..."
        scope.launch(Dispatchers.IO) {
            val ok = PreWipeBackup.run(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    setBusyUi(false)
                    tvStatus.visibility = View.GONE
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("فشلت النسخة الاحتياطية")
                        .setMessage("مش قادر أكتب النسخة الاحتياطية على التخزين.\n" +
                            "اتأكد إن إذن الوصول لكل الملفات مفعّل (القائمة ← الوصول للتخزين) وحاول تاني.\n" +
                            "تم الإلغاء — لم يُمسح أي شيء.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    Toast.makeText(this@MainActivity,
                        "تمت النسخة الاحتياطية في UbuntuTerminal/wipe-backup",
                        Toast.LENGTH_LONG).show()
                    realStartInstall()
                }
            }
        }
    }

    private fun realStartInstall() {
        if (installJob?.isActive == true) return
        setBusyUi(true)

        val installer = Installer(this, object : Installer.Listener {
            override fun onStage(stage: Installer.Stage) {
                runOnUiThread {
                    tvStatus.text = when (stage) {
                        Installer.Stage.DOWNLOAD -> getString(R.string.ut_stage_downloading)
                        Installer.Stage.EXTRACT -> getString(R.string.ut_stage_extracting)
                        Installer.Stage.CONFIGURE -> getString(R.string.ut_stage_configuring)
                        Installer.Stage.DONE -> getString(R.string.ut_stage_done)
                    }
                }
            }

            override fun onProgress(percent: Int, detail: String) {
                runOnUiThread {
                    if (percent >= 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = percent
                    } else {
                        progressBar.isIndeterminate = true
                    }
                    tvProgress.text = detail
                }
            }
        })

        installJob = scope.launch {
            try {
                installer.install()
                showTerminal()
            } catch (e: Exception) {
                setBusyUi(false)
                tvError.visibility = View.VISIBLE
                tvError.text = if (e is Installer.UnsupportedArchitectureException) {
                    getString(R.string.ut_error_unsupported_arch, e.message)
                } else {
                    getString(R.string.ut_install_failed) + "\n" + (e.message ?: e.javaClass.simpleName)
                }
                btnInstall.text = getString(R.string.ut_btn_retry)
            }
        }
    }

    // ---------------------------------------------------------------- terminal

    private fun showTerminal() {
        installJob?.cancel()
        setupPane.visibility = View.GONE
        terminalPane.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ---- pre-flight: verify the rootfs before handing it to PRoot ----
        // Runs on a worker thread: repairing exec bits may walk the whole rootfs.
        scope.launch {
            val (status, report) = withContext(Dispatchers.IO) {
                RootfsDoctor.quickCheck(this@MainActivity)
            }
            when (status) {
                RootfsDoctor.Status.NEEDS_REINSTALL -> {
                    // v1.29: NEVER route to the setup screen from here -
                    // that path is how users lost their whole Ubuntu after an
                    // apt upgrade left a probed file missing. Stay in the
                    // terminal; reinstalling stays available from the menu
                    // (with confirmation + automatic backup).
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚠️ ملفات نظامية ناقصة")
                        .setMessage("بعض أدوات النظام الأساسية ناقصة:\n$report\n\n" +
                            "بياناتك في أمان ولم يُمسح شيء.\n\n" +
                            "جرّب تصلّحها من التيرمنال نفسه بالأمر:\n" +
                            "apt update && apt install --reinstall coreutils bash\n\n" +
                            "ولو التيرمنال مش شغال، تعمل إعادة تثبيت من القائمة — " +
                            "وهياخد نسخة احتياطية تلقائياً قبل المسح.")
                        .setPositiveButton("متابعة للطرفية", null)
                        .show()
                }
                RootfsDoctor.Status.BLOCKED -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.ut_exec_blocked_title)
                        .setMessage(getString(R.string.ut_exec_blocked_msg, report))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }
                RootfsDoctor.Status.REPAIRED ->
                    Toast.makeText(this@MainActivity, R.string.ut_repair_applied, Toast.LENGTH_LONG).show()
                RootfsDoctor.Status.OK -> Unit // healthy
            }
            attachSessionView()
        }
    }

    /**
     * Brings the terminal view up: binds every live session to a client that
     * routes callbacks into THIS activity instance, creates the very first
     * session when needed and attaches the active one to the view.
     */
    private fun attachSessionView() {
        val client = TermSessionClient(this)
        sessionClient = client
        TerminalManager.rebind(client)

        if (TerminalManager.entries.isEmpty()) {
            TerminalManager.createSession(this, client)
        }
        val entry = TerminalManager.active ?: TerminalManager.entries.first()
        TerminalManager.activate(entry)

        terminalView.setTerminalViewClient(TermViewClient(this))
        // NOTE: do NOT call setTypeface() here – it reads mRenderer.mTextSize and the
        // renderer does not exist until the view has been laid out (NullPointerException).
        // setTextSize() is null-safe: it creates the renderer with Typeface.MONOSPACE.
        terminalFontSize = readSetting("font", "14").toIntOrNull() ?: 14
        terminalView.setTextSize(terminalFontSize)
        applyCurrentTheme()
        terminalView.attachSession(entry.session)
        terminalView.requestFocus()
        refreshSessionsUi()
        TerminalService.start(this, TerminalManager.entries.size)
        AndroidApiBridge.start(this) // dapi bridge – independent of the service
        maybeAutoRequestStorage()
    }

    /** Attaches the (new) active session to the view. */
    private fun attachActiveEntry() {
        val entry = TerminalManager.active ?: TerminalManager.entries.firstOrNull() ?: return
        TerminalManager.activate(entry)
        terminalView.attachSession(entry.session)
        terminalView.requestFocus()
        refreshSessionsUi()
    }

    // ---------------------------------------------------------------- sessions

    private fun newSession() {
        val client = sessionClient ?: return
        val entry = TerminalManager.createSession(this, client)
        TerminalManager.activate(entry)
        terminalView.attachSession(entry.session)
        terminalView.requestFocus()
        refreshSessionsUi()
        TerminalService.start(this, TerminalManager.entries.size)
        closeSessionsDrawer()
    }

    private fun closeSession(entry: TerminalManager.Entry) {
        if (entry.session.isRunning) {
            entry.session.finishIfRunning() // the exit callback cleans up & reattaches
        } else {
            // never started – no exit callback will ever arrive
            TerminalManager.remove(entry.session)
            onEntriesChanged()
        }
    }

    /** Kills the active session and immediately starts a fresh one. */
    private fun restartActiveSession() {
        val client = sessionClient ?: return
        TerminalManager.active?.let { old ->
            old.session.finishIfRunning()
            if (!old.session.isRunning) TerminalManager.remove(old.session)
        }
        val entry = TerminalManager.createSession(this, client)
        TerminalManager.activate(entry)
        terminalView.attachSession(entry.session)
        terminalView.requestFocus()
        refreshSessionsUi()
        TerminalService.start(this, TerminalManager.entries.size)
    }

    /** Called after the set of live sessions changed (removal paths). */
    private fun onEntriesChanged() {
        if (TerminalManager.entries.isEmpty()) {
            TerminalService.stop(this)
            refreshSessionsUi()
            showAllSessionsEndedDialog()
        } else {
            attachActiveEntry()
            TerminalService.start(this, TerminalManager.entries.size)
        }
    }

    private fun showAllSessionsEndedDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ut_session_ended)
            .setPositiveButton(R.string.ut_btn_restart_session) { _, _ ->
                showTerminal()
            }
            .setNegativeButton(R.string.ut_btn_back_to_setup) { _, _ ->
                showSetup()
            }
            .setCancelable(false)
            .show()
    }

    /** Called by [TermSessionClient] whenever new output is available. */
    fun onTerminalTextChanged(changed: TerminalSession) {
        if (::terminalView.isInitialized && TerminalManager.activeSession === changed) {
            terminalView.onScreenUpdated()
        }
    }

    /** Called by [TermSessionClient] when a shell process has exited. */
    fun onTerminalSessionFinished(finished: TerminalSession) {
        val removed = TerminalManager.remove(finished) ?: return
        onEntriesChanged()
        if (!isFinishing && !isDestroyed && TerminalManager.entries.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.ut_session_ended_toast_fmt, removed.title),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------------------------------------------------------- sessions drawer

    private fun openSessionsDrawer() {
        refreshSessionsUi()
        sessionDim.visibility = View.VISIBLE
        sessionDim.alpha = 0f
        sessionDim.animate().alpha(1f).setDuration(180).start()
        sessionsDrawer.visibility = View.VISIBLE
        sessionsDrawer.post {
            val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val w = sessionsDrawer.width.toFloat()
            sessionsDrawer.translationX = if (rtl) -w else w
            sessionsDrawer.animate().translationX(0f).setDuration(200).start()
        }
    }

    private fun closeSessionsDrawer() {
        sessionDim.visibility = View.GONE
        sessionsDrawer.visibility = View.GONE
        sessionsDrawer.translationX = 0f
        if (terminalPane.visibility == View.VISIBLE) terminalView.requestFocus()
    }

    /** Rebuilds the session rows inside the drawer. */
    private fun refreshSessionsUi() {
        if (!::sessionsList.isInitialized) return
        sessionsList.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        for (entry in TerminalManager.entries) {
            val isActive = TerminalManager.active === entry
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(4), dp(6))
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isActive) R.drawable.ut_key_bg_active else R.drawable.ut_key_bg
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                setOnClickListener {
                    TerminalManager.activate(entry)
                    attachActiveEntry()
                    closeSessionsDrawer()
                }
            }
            row.addView(TextView(this).apply {
                text = entry.title
                textSize = 16f
                // active row has an orange background → WHITE bold text,
                // inactive rows are dark → light text
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFF2F2F2.toInt())
                typeface = if (isActive) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 15f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt())
                setOnClickListener { closeSession(entry) }
            })
            sessionsList.addView(row)
        }
    }

    // ---------------------------------------------------------------- device storage

    /** True when the app may read/write the device's shared storage. */
    private fun hasStorageAccess(): Boolean {
        val granted = STORAGE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) return true
        // Android 11+ alternative: the "All files access" toggle
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    }

    /** Asks for storage once per install, right after the terminal first appears. */
    private fun maybeAutoRequestStorage() {
        val prefs = getSharedPreferences("ubuntu_terminal", Context.MODE_PRIVATE)
        if (prefs.getBoolean("storage_asked", false)) return
        prefs.edit().putBoolean("storage_asked", true).apply()
        if (!hasStorageAccess()) {
            ActivityCompat.requestPermissions(this, STORAGE_PERMISSIONS, REQ_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Toast.makeText(
            this,
            if (ok) R.string.ut_storage_granted_toast else R.string.ut_storage_denied_toast,
            Toast.LENGTH_LONG
        ).show()
        if (!ok) showStorageDialog() // offer the "All files access" route as plan B
    }

    private fun showStorageDialog() {
        val granted = hasStorageAccess()
        val msg = getString(R.string.ut_storage_msg) + "\n\n" +
            getString(if (granted) R.string.ut_storage_status_granted else R.string.ut_storage_status_denied)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ut_storage_title)
            .setMessage(msg)
            .setPositiveButton(if (granted) android.R.string.ok else R.string.ut_btn_grant) { d, _ ->
                if (!granted) ActivityCompat.requestPermissions(this, STORAGE_PERMISSIONS, REQ_STORAGE)
                d.dismiss()
            }
            .setNegativeButton(R.string.ut_btn_later, null)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            dialog.setNeutralButton(R.string.ut_btn_all_files) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- clipboard

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, R.string.ut_copied, Toast.LENGTH_SHORT).show()
    }

    fun pasteIntoSession(session: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.ut_nothing_to_paste, Toast.LENGTH_SHORT).show()
            return
        }
        // terminals expect CR as the line terminator
        session.write(text.replace("\r\n", "\r").replace("\n", "\r"))
        completion.onPaste(text)
    }

    // ---------------------------------------------------------------- settings

    fun readSetting(key: String, default: String): String =
        getSharedPreferences("ut_settings", MODE_PRIVATE).getString(key, default) ?: default

    fun saveSetting(key: String, value: String) {
        getSharedPreferences("ut_settings", MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun applyCurrentTheme() {
        val theme = TerminalTheme.fromId(readSetting("theme", "dark"))
        terminalView.setBackgroundColor(theme.bg)
        TerminalManager.entries.forEach { e -> runCatching { theme.applyTo(e.session) } }
        runCatching { terminalView.onScreenUpdated() }
    }

    // ---------------------------------------------------------------- menu

    private fun showTerminalMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(R.string.ut_menu_settings).setOnMenuItemClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            true
        }
        menu.menu.add(R.string.ut_menu_monitor).setOnMenuItemClickListener {
            startActivity(android.content.Intent(this, MonitorActivity::class.java))
            true
        }
        menu.menu.add(R.string.ut_menu_files).setOnMenuItemClickListener {
            startActivity(android.content.Intent(this, FileManagerActivity::class.java))
            true
        }
        menu.menu.add(R.string.ut_menu_sessions).setOnMenuItemClickListener {
            openSessionsDrawer()
            true
        }
        menu.menu.add(R.string.ut_menu_paste).setOnMenuItemClickListener {
            TerminalManager.activeSession?.let(::pasteIntoSession)
            true
        }
        menu.menu.add(R.string.ut_menu_restart).setOnMenuItemClickListener {
            restartActiveSession()
            true
        }
        menu.menu.add(R.string.ut_menu_screenfix).setOnMenuItemClickListener {
            confirmScreenFix()
            true
        }
        menu.menu.add(R.string.ut_menu_storage).setOnMenuItemClickListener {
            showStorageDialog()
            true
        }
        menu.menu.add(R.string.ut_menu_reinstall).setOnMenuItemClickListener {
            confirmReinstall()
            true
        }
        menu.menu.add(R.string.ut_menu_diagnose).setOnMenuItemClickListener {
            runDiagnostics()
            true
        }
        menu.menu.add(R.string.ut_menu_about).setOnMenuItemClickListener {
            showAbout()
            true
        }
        menu.menu.add(R.string.ut_menu_exit).setOnMenuItemClickListener {
            TerminalManager.reset()
            TerminalService.stop(this)
            finish()
            true
        }
        menu.show()
    }

    /**
     * Heals a terminal left in a broken state by a killed TUI (alternate
     * screen still active / mouse tracking still on / cursor hidden). The
     * sequences are written to the session input, so the shell echoes them
     * back through the output path where the emulator actually processes
     * them. Harmless at a healthy prompt - everything is already reset.
     */
    /**
     * Connects to the dapi bridge from inside the app and asks for a ping,
     * so the diagnostics can tell a broken bridge apart from a broken
     * script/network path inside the container.
     */
    private fun bridgeSelfTest(): String = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 8037), 1000)
            s.soTimeout = 2000
            s.getOutputStream().apply {
                write("ping\n".toByteArray(Charsets.UTF_8))
                flush()
            }
            val reply = s.getInputStream().bufferedReader().readLine() ?: ""
            if (reply.startsWith("pong")) "OK - $reply" else reply.ifEmpty { "(no reply)" }
        }
    } catch (e: Exception) {
        "FAIL - " + (e.message ?: e.javaClass.simpleName)
    }

    private fun confirmScreenFix() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ut_screenfix_title)
            .setMessage(R.string.ut_screenfix_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TerminalManager.activeSession?.write(
                    "\u001B[?1000l\u001B[?1002l\u001B[?1003l\u001B[?1006l\u001B[?1015l" +
                        "\u001B[?25h\u001B[?2004l\u001B[?1049l\u001B[0m"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Collects the diagnostics report on a worker thread. Walking the whole
     * rootfs on the main thread caused an ANR when the device was busy.
     */
    private fun runDiagnostics() {
        val collecting = AlertDialog.Builder(this)
            .setTitle(R.string.ut_diag_title)
            .setView(ProgressBar(this))
            .setCancelable(true)
            .show()
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                RootfsDoctor.fullReport(this@MainActivity) + "\n\n" +
                    getString(R.string.ut_diag_bridge_fmt, bridgeSelfTest())
            }
            collecting.dismiss()
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.ut_diag_title)
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmReinstall() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ut_confirm_reinstall_title)
            .setMessage(getString(R.string.ut_confirm_reinstall_msg) +
                "\n\nسيتم أخذ نسخة احتياطية تلقائية (ملفاتك + قايمة البرامج) في مجلد UbuntuTerminal قبل المسح.")
            .setPositiveButton(R.string.ut_dialog_yes) { _, _ ->
                setBusyUi(true)
                tvStatus.visibility = View.VISIBLE
                tvStatus.setText(R.string.ut_stage_configuring)
                scope.launch(Dispatchers.IO) {
                    TerminalManager.reset()
                    // v1.29: never delete without a backup.
                    val backedUp = PreWipeBackup.run(this@MainActivity)
                    Installer.deleteInstall(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        TerminalService.stop(this@MainActivity)
                        installJob = null
                        showSetup()
                        if (backedUp) Toast.makeText(this@MainActivity,
                            "تم أخذ نسخة احتياطية في UbuntuTerminal/wipe-backup قبل المسح",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.ut_dialog_no, null)
            .show()
    }

    private fun showAbout() {
        @Suppress("DEPRECATION")
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        AlertDialog.Builder(this)
            .setTitle(R.string.ut_about_title)
            .setMessage(getString(R.string.ut_about_text) + "\n\n" + getString(R.string.ut_app_version_fmt, ver))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
