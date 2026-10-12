package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Dịch vụ nền: giữ quyền chụp màn hình (MediaProjection), hiển thị nút nổi,
 * chụp màn hình -> OCR -> Google Dịch -> vẽ bản dịch đè lên màn hình.
 */
class ScreenTranslateService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.dichmanhinh.screentranslator.STOP"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ocr = OcrEngine()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var lastBitmap: Bitmap? = null

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection == null) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null || !setupProjection(code, data)) {
                Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (bubble == null) showBubble()
        isRunning = true
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- Foreground

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP), piFlags
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- Capture

    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun setupProjection(code: Int, data: Intent): Boolean {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: return false
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)
        projection = mp
        val (w, h, dpi) = screenSize()
        capW = w; capH = h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = runCatching {
            mp.createVirtualDisplay(
                "ScreenTranslator", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler
            )
        }.getOrNull()
        return virtualDisplay != null
    }

    /** Điều chỉnh kích thước khi xoay màn hình. Trả về true nếu có thay đổi. */
    private fun ensureSize(): Boolean {
        val vd = virtualDisplay ?: return false
        val (w, h, dpi) = screenSize()
        if (w == capW && h == capH) return false
        val old = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        vd.resize(w, h, dpi)
        vd.surface = reader.surface
        old?.close()
        capW = w; capH = h
        lastBitmap = null
        return true
    }

    private fun drainImages() {
        runCatching { imageReader?.acquireLatestImage()?.close() }
    }

    private fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val raw = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            if (raw.width == w) raw else Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
        } finally {
            image.close()
        }
    }

    /** Chụp màn hình (đã ẩn nút nổi và lớp phủ). */
    private suspend fun capture(): Bitmap? {
        if (ensureSize()) delay(400)
        drainImages()
        bubble?.visibility = View.INVISIBLE
        delay(220)
        var bmp: Bitmap? = null
        for (i in 0 until 8) {
            bmp = withContext(Dispatchers.Default) { acquireBitmap() }
            if (bmp != null) break
            delay(80)
        }
        bubble?.visibility = View.VISIBLE
        if (bmp != null) lastBitmap = bmp
        return bmp ?: lastBitmap
    }

    // ---------------------------------------------------------------- Bubble

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showBubble() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                startRegionSelect()
            }
        }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    mainHandler.postDelayed(longPress, longPressTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved && !longPressed) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (moved) snapToEdge(v, lp)
                    else if (!longPressed) translateScreen(null)
                }
                MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
        bubbleParams = lp
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val (w, _, _) = screenSize()
        val center = lp.x + v.width / 2
        lp.x = if (center < w / 2) 0 else w - v.width
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun setLoading(loading: Boolean) {
        val b = bubble ?: return
        b.findViewById<ProgressBar>(R.id.bubbleProgress).visibility = if (loading) View.VISIBLE else View.GONE
        b.findViewById<View>(R.id.bubbleIcon).visibility = if (loading) View.INVISIBLE else View.VISIBLE
    }

    // ---------------------------------------------------------------- Overlays

    private fun fullScreenParams(focusable: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun showOverlay(v: View) {
        removeOverlay()
        wm.addView(v, fullScreenParams())
        overlay = v
        // Đưa nút nổi lên trên cùng
        bubble?.let { b ->
            runCatching {
                wm.removeView(b)
                wm.addView(b, bubbleParams)
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun startRegionSelect() {
        if (busy) return
        val view = RegionSelectView(
            this,
            onSelected = { rect ->
                removeOverlay()
                translateScreen(rect)
            },
            onCancel = { removeOverlay() }
        )
        showOverlay(view)
    }

    // ---------------------------------------------------------------- Translate

    private fun translateScreen(region: Rect?) {
        if (busy) return
        if (overlay != null) {
            removeOverlay()
            if (region == null) return
        }
        busy = true
        scope.launch {
            try {
                delay(60)
                val shot = capture()
                if (shot == null) {
                    toast(getString(R.string.capture_failed))
                    return@launch
                }
                setLoading(true)
                val src = Prefs.source(this@ScreenTranslateService)
                val tgt = Prefs.target(this@ScreenTranslateService)

                val area = region?.let {
                    Rect(it).apply {
                        if (!intersect(0, 0, shot.width, shot.height)) setEmpty()
                    }
                }
                if (area != null && area.isEmpty) {
                    toast(getString(R.string.no_text))
                    return@launch
                }
                val input = if (area != null) {
                    Bitmap.createBitmap(shot, area.left, area.top, area.width(), area.height())
                } else shot

                val blocks = ocr.recognize(input, src).map { b ->
                    if (area != null) OcrBlock(b.text, Rect(b.rect).apply { offset(area.left, area.top) }) else b
                }
                if (blocks.isEmpty()) {
                    toast(getString(R.string.no_text))
                    return@launch
                }
                val translated = GoogleTranslator.translateAll(blocks.map { it.text }, src, tgt)
                val items = blocks.mapIndexed { i, b -> TranslatedBlock(b.text, translated[i], b.rect) }
                showResult(items, area)
            } catch (e: Exception) {
                toast("Lỗi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setLoading(false)
                busy = false
            }
        }
    }

    private fun showResult(items: List<TranslatedBlock>, area: Rect?) {
        val view = TranslationOverlayView(
            this, items, area,
            onClose = { removeOverlay() },
            onCopy = {
                val text = items.joinToString("\n") { it.translated }
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", text))
                toast(getString(R.string.copied))
            }
        )
        showOverlay(view)
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- Cleanup

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        removeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        projection = null
        ocr.close()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
