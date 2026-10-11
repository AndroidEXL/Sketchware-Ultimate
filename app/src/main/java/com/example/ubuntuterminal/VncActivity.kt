package com.example.ubuntuterminal

import pro.sketchware.R

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Embedded VNC viewer: shows the Ubuntu desktop (TigerVNC on 127.0.0.1:5901,
 * started via `dapi vnc start`) directly inside the app. Self-contained RFB
 * 3.8 client - no external libraries.
 */
class VncActivity : Activity() {

    private lateinit var view: VncView
    private lateinit var status: TextView
    private lateinit var kbd: EditText
    private var client: VncClient? = null
    private var ctrlOn = false
    private var altOn = false
    private var lastLen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ut_activity_vnc)

        view = findViewById(R.id.vnc_view)
        status = findViewById(R.id.vnc_status)
        kbd = findViewById(R.id.vnc_kbd)
        val btnKbd: Button = findViewById(R.id.vnc_btn_kbd)
        val btnCtrl: Button = findViewById(R.id.vnc_btn_ctrl)
        val btnAlt: Button = findViewById(R.id.vnc_btn_alt)
        val btnFit: Button = findViewById(R.id.vnc_btn_fit)
        val btnClose: Button = findViewById(R.id.vnc_btn_close)

        btnKbd.setOnClickListener { toggleKeyboard() }
        btnCtrl.setOnClickListener {
            ctrlOn = !ctrlOn; altOn = false
            btnCtrl.isSelected = ctrlOn; btnAlt.isSelected = altOn
        }
        btnAlt.setOnClickListener {
            altOn = !altOn; ctrlOn = false
            btnAlt.isSelected = altOn; btnCtrl.isSelected = ctrlOn
        }
        btnFit.setOnClickListener { view.fitScreen() }
        btnClose.setOnClickListener { finish() }

        kbd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                val c = client ?: return
                val n = s.length
                if (n > lastLen) {
                    for (ch in s.substring(lastLen)) sendChar(ch)
                } else if (n < lastLen) {
                    repeat(lastLen - n) { sendKey(0xFF08) }
                }
                lastLen = n
            }
        })

        connect()
    }

    private fun connect() {
        status.text = "... 127.0.0.1:5901\nrun: dapi vnc start"
        client = VncClient("127.0.0.1", 5901, object : VncClient.Callback {
            override fun onConnected(name: String, w: Int, h: Int) {
                runOnUiThread {
                    status.visibility = View.GONE
                    client?.let { view.bind(it) }
                    Toast.makeText(this@VncActivity, "$name  ${w}x$h", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onUpdate() = view.postInvalidate()

            override fun onStatus(msg: String) {
                runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = msg
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            }
        })
        client?.start()
    }

    private fun toggleKeyboard() {
        if (kbd.visibility == View.VISIBLE) {
            kbd.visibility = View.GONE
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(kbd.windowToken, 0)
        } else {
            kbd.visibility = View.VISIBLE
            kbd.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(kbd, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** sends a character, applying the Ctrl/Alt toggles once (held-then-released). */
    private fun sendChar(ch: Char) {
        val sym = if (ch.code in 0x20..0xFF) ch.code else 0x01000000 + ch.code
        val c = client ?: return
        if (ctrlOn) c.keyEvent(0xFFE3, true)   // Control_L down
        if (altOn) c.keyEvent(0xFFE9, true)    // Alt_L down
        c.keyEvent(sym, true); c.keyEvent(sym, false)
        if (ctrlOn) { c.keyEvent(0xFFE3, false); ctrlOn = false; findViewById<Button>(R.id.vnc_btn_ctrl).isSelected = false }
        if (altOn) { c.keyEvent(0xFFE9, false); altOn = false; findViewById<Button>(R.id.vnc_btn_alt).isSelected = false }
    }

    private fun sendKey(sym: Int) {
        client?.keyEvent(sym, true)
        client?.keyEvent(sym, false)
    }

    override fun onDestroy() {
        client?.shutdown()
        super.onDestroy()
    }
}

/** Renders the remote framebuffer; 1 finger = pointer, 2 fingers = pan/zoom,
 *  long-press = right click. */
class VncView @JvmOverloads constructor(
    ctx: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var client: VncClient? = null
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pinchDist = 0f
    private var pinchScale = 1f
    private val longPress = Runnable {
        if (!moved) {
            client?.pointerEvent(4, fbX(lastX), fbY(lastY))   // right down
            client?.pointerEvent(0, fbX(lastX), fbY(lastY))   // up
        }
    }

    fun bind(c: VncClient) {
        client = c
        fitScreen()
    }

    fun fitScreen() {
        val c = client ?: return
        if (c.fbWidth == 0 || width == 0) return
        scale = min(width.toFloat() / c.fbWidth, height.toFloat() / c.fbHeight)
        offX = (width - c.fbWidth * scale) / 2f
        offY = (height - c.fbHeight * scale) / 2f
        invalidate()
    }

    private fun fbX(sx: Float): Int {
        val c = client ?: return 0
        return max(0, min(c.fbWidth - 1, ((sx - offX) / scale).toInt()))
    }

    private fun fbY(sy: Float): Int {
        val c = client ?: return 0
        return max(0, min(c.fbHeight - 1, ((sy - offY) / scale).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fitScreen()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val c = client ?: return
        val bmp = c.bitmap ?: return
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; moved = false
                postDelayed(longPress, 550)
                c.pointerEvent(1, fbX(e.x), fbY(e.y))
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                c.pointerEvent(0, fbX(e.x), fbY(e.y))         // release while panning
                pinchDist = spacing(e)
                pinchScale = scale
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && pinchDist > 10f) {
                    val ns = (pinchScale * spacing(e) / pinchDist).coerceIn(0.2f, 12f)
                    offX = e.x - (e.x - offX) * ns / scale
                    offY = e.y - (e.y - offY) * ns / scale
                    scale = ns
                    clamp()
                    invalidate()
                } else if (e.pointerCount == 1) {
                    if (abs(e.x - lastX) > 8 || abs(e.y - lastY) > 8) {
                        moved = true; removeCallbacks(longPress)
                    }
                    lastX = e.x; lastY = e.y
                    c.pointerEvent(1, fbX(e.x), fbY(e.y))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinchDist = 0f
                c.pointerEvent(0, fbX(e.x), fbY(e.y))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                c.pointerEvent(0, fbX(e.x), fbY(e.y))
                return true
            }
        }
        return false
    }

    private fun clamp() {
        val c = client ?: return
        val w = c.fbWidth * scale
        val h = c.fbHeight * scale
        offX = if (w <= width) (width - w) / 2f else max(min(offX, 0f), width - w)
        offY = if (h <= height) (height - h) / 2f else max(min(offY, 0f), height - h)
    }

    private fun spacing(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}

/** Minimal RFB 3.8 client: Raw + CopyRect encodings, None auth on localhost. */
class VncClient(
    private val host: String,
    private val port: Int,
    private val cb: Callback
) : Thread() {

    interface Callback {
        fun onConnected(name: String, w: Int, h: Int)
        fun onUpdate()
        fun onStatus(msg: String)
    }

    @Volatile private var running = true
    @Volatile var bitmap: Bitmap? = null
    var fbWidth = 0; private set
    var fbHeight = 0; private set

    private lateinit var sock: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val outLock = Any()
    private val bmpLock = Any()

    override fun run() {
        try {
            sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 5000)
            input = DataInputStream(sock.getInputStream().buffered(64 * 1024))
            output = sock.getOutputStream()

            // --- version handshake (server sends its max, we answer <= it)
            val ver = ByteArray(12)
            readFully(ver)
            val minor = parseMinor(ver)
            val myMinor = if (minor >= 8) 8 else minor
            output.write("RFB 003.%03d\n".format(myMinor).toByteArray(Charsets.US_ASCII))
            output.flush()

            // --- security: pick None (1)
            if (myMinor >= 7) {
                val n = input.read()
                if (n <= 0) {
                    if (myMinor >= 8) { val l = input.readInt(); skip(l) }
                    fail("server offered no security types")
                }
                val types = ByteArray(n); readFully(types)
                if (!types.contains(1.toByte()))
                    fail("server requires a password (expected None)")
                output.write(1); output.flush()
                if (myMinor >= 8 && input.readInt() != 0)
                    fail("security handshake rejected")
            } else {                                        // RFB 3.3: u32 type
                if (input.readInt() != 1)
                    fail("server requires a password (expected None)")
            }

            // --- client/server init
            output.write(1); output.flush()                    // shared
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            skip(16)                                           // server pixel format
            val nameLen = input.readInt()
            val name = ByteArray(nameLen.coerceAtLeast(0)); readFully(name)

            synchronized(bmpLock) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            fbWidth = w; fbHeight = h
            cb.onConnected(String(name, Charsets.ISO_8859_1), w, h)

            // --- our pixel format: 32bpp truecolour LE, shifts 16/8/0
            sendRaw(byteArrayOf(
                0, 0, 0, 0,               // SetPixelFormat + padding
                32, 24, 0, 1,             // bpp, depth, little-endian, truecolour
                0, 0xFF.toByte(), 0, 0xFF.toByte(), 0, 0xFF.toByte(),  // rgb max = 255 BE
                16, 8, 0, 0, 0, 0         // rgb shifts + padding
            ))
            // --- encodings: Raw(0) + CopyRect(1)
            sendRaw(byteArrayOf(2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1))

            requestUpdate(false)
            while (running) {
                when (val type = input.read()) {
                    0 -> {                                 // FramebufferUpdate
                        skip(1)
                        val nRects = input.readUnsignedShort()
                        for (r in 0 until nRects) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            when (input.readInt()) {
                                0 -> decodeRaw(x, y, rw, rh)
                                1 -> {                     // CopyRect
                                    val sx = input.readUnsignedShort()
                                    val sy = input.readUnsignedShort()
                                    copyRect(sx, sy, x, y, rw, rh)
                                }
                                else -> fail("unsupported encoding")
                            }
                        }
                        cb.onUpdate()
                        requestUpdate(true)
                    }
                    1 -> { skip(1); val first = input.readUnsignedShort(); val n = input.readUnsignedShort(); skip(n * 6) }
                    2 -> { /* bell */ }
                    3 -> { skip(3); val len = input.readInt(); skip(len) }
                    -1 -> fail("connection closed by server")
                    else -> fail("unknown message $type")
                }
            }
        } catch (e: Exception) {
            if (running) cb.onStatus("VNC: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val n = w * h
        val bytes = ByteArray(n * 4)
        readFully(bytes)
        val px = IntArray(n)
        for (i in 0 until n) {
            val o = i * 4
            px[i] = 0xFF shl 24 or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                (bytes[o].toInt() and 0xFF)
        }
        synchronized(bmpLock) {
            bitmap?.setPixels(px, 0, w, x, y, w, h)
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        val tmp = IntArray(w * h)
        synchronized(bmpLock) {
            val b = bitmap ?: return
            b.getPixels(tmp, 0, w, sx, sy, w, h)
            b.setPixels(tmp, 0, w, x, y, w, h)
        }
    }

    fun pointerEvent(buttons: Int, x: Int, y: Int) {
        val m = byteArrayOf(5, buttons.toByte(), (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte())
        runCatching { sendRaw(m) }
    }

    fun keyEvent(sym: Int, down: Boolean) {
        val m = byteArrayOf(4, if (down) 1 else 0, 0, 0,
            ((sym shr 24) and 0xFF).toByte(), ((sym shr 16) and 0xFF).toByte(),
            ((sym shr 8) and 0xFF).toByte(), (sym and 0xFF).toByte())
        runCatching { sendRaw(m) }
    }

    private fun requestUpdate(incremental: Boolean) {
        sendRaw(byteArrayOf(
            3, if (incremental) 1 else 0,
            0, 0, 0, 0,                                 // x = 0, y = 0
            (fbWidth shr 8).toByte(), fbWidth.toByte(),
            (fbHeight shr 8).toByte(), fbHeight.toByte()
        ))
    }

    private fun sendRaw(b: ByteArray) {
        synchronized(outLock) {
            output.write(b); output.flush()
        }
    }

    private fun readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw java.io.EOFException("unexpected EOF")
            off += n
        }
    }

    private fun skip(n: Int) {
        var left = n.toLong()
        while (left > 0) left -= input.skip(left)
    }

    private fun parseMinor(ver: ByteArray): Int {
        val s = String(ver, Charsets.US_ASCII)     // "RFB 003.008\n"
        return runCatching { s.substring(8, 11).trim().toInt() }.getOrDefault(8)
    }

    private fun fail(msg: String): Nothing {
        throw java.io.IOException(msg)
    }

    fun shutdown() {
        running = false
        runCatching { if (::sock.isInitialized) sock.close() }
    }
}
