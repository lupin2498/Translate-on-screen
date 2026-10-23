package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dichmanhinh.screentranslator.ai.AiImage
import com.dichmanhinh.screentranslator.ai.AiImages
import com.dichmanhinh.screentranslator.ai.AiTranslator
import com.dichmanhinh.screentranslator.ai.LocalAi
import com.dichmanhinh.screentranslator.ai.ModelManager
import com.dichmanhinh.screentranslator.ai.NativeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Dịch vụ nền: giữ quyền chụp màn hình (MediaProjection), hiển thị nút nổi,
 * chụp màn hình -> OCR -> Google Dịch -> vẽ bản dịch đè lên màn hình.
 *
 * Hỗ trợ cả hai chế độ chia sẻ của Android 14+: "Toàn bộ màn hình" và "Một ứng dụng".
 */
class ScreenTranslateService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.dichmanhinh.screentranslator.STOP"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIF_ID = 1001
        private const val TAG = "ScreenTranslator"

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var uiContext: Context
    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val detector = LanguageDetector()
    private val ocr by lazy { OcrEngine(applicationContext, detector) }

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var projectionStartedAt = 0L

    /**
     * true khi lớp phủ của ta nằm trong ảnh chụp (chia sẻ toàn màn hình) – cần ẩn nút nổi và chờ
     * khung hình mới. Ở chế độ một ứng dụng, ẩn nút nổi không tạo khung hình mới.
     */
    private var overlayInCapture = true

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var busy = false
    private var stopping = false
    private var translateJob: Job? = null

    /** Đang chạy AI trên máy (chạm nút nổi lúc này để hủy). */
    private var aiRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        uiContext = createUiContext()
        wm = uiContext.getSystemService(WindowManager::class.java)
    }

    /** Context dành cho cửa sổ nổi (API 30+ dùng window context để có cấu hình/kích thước đúng). */
    private fun createUiContext(): Context {
        val base: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val dm = getSystemService(DisplayManager::class.java)
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrElse { this }
        } else this
        return ContextThemeWrapper(base, R.style.Theme_DichManHinh)
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
                DebugInfo.lastError = "setupProjection failed"
                Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (bubble == null) {
            showBubble()
            val src = Prefs.source(this)
            scope.launch(Dispatchers.Default) { ocr.warmUp(src) }
        }
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

    /** Kích thước thật của toàn màn hình (pixel) và mật độ điểm ảnh. */
    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = uiContext.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun setupProjection(code: Int, data: Intent): Boolean {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        } ?: return false
        val cap = ScreenCapturer(mp, captureHandler)
        // Callback phải được đăng ký trước createVirtualDisplay (Android 14+)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped")
                mainHandler.post {
                    if (!stopping) {
                        Toast.makeText(this@ScreenTranslateService, R.string.projection_stopped, Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                cap.onContentResized(width, height)
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                cap.onContentVisibilityChanged(isVisible)
                mainHandler.post { onContentVisibilityChanged(isVisible) }
            }
        }, captureHandler)
        projection = mp
        capturer = cap
        overlayInCapture = true
        val (w, h, dpi) = screenSize()
        if (!cap.start(w, h, dpi)) {
            runCatching { mp.stop() }
            projection = null
            capturer = null
            return false
        }
        projectionStartedAt = SystemClock.uptimeMillis()
        return true
    }

    private fun onContentVisibilityChanged(visible: Boolean) {
        bubble?.alpha = if (visible) 1f else 0.5f
        if (!visible && SystemClock.uptimeMillis() - projectionStartedAt < 2500) {
            // Thường do người dùng chọn chia sẻ chính ứng dụng này
            toast(getString(R.string.shared_self), long = true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Xoay màn hình: trên Android 14+ hệ thống báo qua onCapturedContentResize
        if (Build.VERSION.SDK_INT < 34) {
            val (w, h, dpi) = screenSize()
            captureHandler.post { capturer?.resize(w, h, dpi) }
        }
        bubbleParams?.let { lp -> bubble?.let { keepBubbleOnScreen(it, lp) } }
    }

    /** Chụp màn hình (ẩn nút nổi nếu nó nằm trong ảnh chụp). */
    private suspend fun capture(): ScreenCapturer.Shot? {
        val cap = capturer ?: return null
        if (Build.VERSION.SDK_INT < 34) {
            val (w, h, dpi) = screenSize()
            if (withContext(Dispatchers.Default) { cap.resize(w, h, dpi) }) delay(300)
        }
        val b = bubble
        // Thông báo (toast) của app cũng nằm trên màn hình -> ẩn để không bị OCR
        lastToast?.cancel()
        lastToast = null
        val since = cap.frames
        b?.visibility = View.INVISIBLE
        try {
            // Toàn màn hình: ẩn nút nổi sẽ tạo khung hình mới. Một ứng dụng: không có khung hình mới.
            val timeout = if (overlayInCapture) 450L else 150L
            val shot = cap.capture(since, timeout)
            val gotNewFrame = cap.frames > since
            if (overlayInCapture && !gotNewFrame && cap.hasFrame) {
                Log.i(TAG, "no new frame after hiding overlay -> single-app capture")
                overlayInCapture = false
            } else if (!overlayInCapture && gotNewFrame && cap.coversScreen(screenSize().first, screenSize().second)) {
                // Có thể là chia sẻ toàn màn hình trên máy chậm
                overlayInCapture = true
            }
            DebugInfo.lastCapture = "singleApp=${!overlayInCapture} newFrame=$gotNewFrame frames=${cap.frames} " +
                "content=${cap.contentWidth}x${cap.contentHeight} rect=${shot?.screenRect}"
            Log.i(TAG, "capture ${DebugInfo.lastCapture}")
            return shot
        } finally {
            b?.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- Bubble

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showBubble() {
        val v = LayoutInflater.from(uiContext).inflate(R.layout.view_bubble, null)
        val size = (56 * uiContext.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (uiContext.resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
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
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                        keepBubbleOnScreen(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (moved) snapToEdge(v, lp)
                    else if (!longPressed) onBubbleTap()
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
        keepBubbleOnScreen(v, lp)
    }

    private fun keepBubbleOnScreen(v: View, lp: WindowManager.LayoutParams) {
        val (w, h, _) = screenSize()
        val size = if (v.width > 0) v.width else lp.width
        lp.x = lp.x.coerceIn(0, (w - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (h - size).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun setLoading(loading: Boolean) {
        val b = bubble ?: return
        b.findViewById<ProgressBar>(R.id.bubbleProgress).visibility = if (loading) View.VISIBLE else View.GONE
        b.findViewById<View>(R.id.bubbleIcon).visibility = if (loading) View.INVISIBLE else View.VISIBLE
        if (!loading) b.findViewById<View>(R.id.bubbleCount).visibility = View.GONE
    }

    /** Hiện "đã dịch/tổng" giữa vòng tiến độ của nút nổi khi AI dịch nhiều đoạn. */
    private fun setAiProgress(done: Int, total: Int) {
        mainHandler.post {
            val tv = bubble?.findViewById<android.widget.TextView>(R.id.bubbleCount) ?: return@post
            if (!busy || total < 2) {
                tv.visibility = View.GONE; return@post
            }
            tv.text = "$done/$total"
            tv.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- Overlays

    private fun fullScreenParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
        try {
            wm.addView(v, fullScreenParams())
        } catch (e: Exception) {
            Log.e(TAG, "addView overlay failed", e)
            toast(getString(R.string.capture_failed))
            return
        }
        overlay = v
        DebugInfo.overlayShown++
        Log.i(TAG, "overlay shown ${v.javaClass.simpleName}")
        // Đưa nút nổi lên trên cùng
        bubble?.let { b ->
            runCatching {
                wm.removeViewImmediate(b)
                wm.addView(b, bubbleParams)
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeViewImmediate(it) } }
        overlay = null
    }

    private fun onBubbleTap() {
        if (busy) {
            // AI trên máy có thể chạy lâu: chạm nút nổi lần nữa để hủy
            if (aiRunning) {
                translateJob?.cancel()
                LocalAi.cancel()
                toast(getString(R.string.ai_cancelled))
            }
            return
        }
        if (overlay != null) {
            // Đang hiện bản dịch / chọn vùng -> chạm nút nổi để đóng
            removeOverlay()
            return
        }
        translateScreen(null)
    }

    private fun startRegionSelect() {
        if (busy) return
        val view = RegionSelectView(
            uiContext,
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
        val cap = capturer
        if (cap == null) {
            toast(getString(R.string.capture_failed)); return
        }
        if (!cap.contentVisible) {
            DebugInfo.lastError = "content hidden"
            toast(getString(R.string.content_hidden), long = true); return
        }
        busy = true
        DebugInfo.translateCalls++
        translateJob = scope.launch {
            try {
                delay(50)
                val shot = capture()
                if (shot == null) {
                    DebugInfo.lastError = "capture returned null"
                    toast(getString(R.string.capture_failed))
                    return@launch
                }
                val full = shot.bitmap
                if (withContext(Dispatchers.Default) { isBlank(full) }) {
                    DebugInfo.lastError = "blank capture"
                    toast(getString(R.string.capture_blank), long = true)
                    return@launch
                }
                setLoading(true)
                val src = Prefs.source(this@ScreenTranslateService)
                val tgt = Prefs.target(this@ScreenTranslateService)
                val (sw, sh, _) = screenSize()
                // Chỉ vẽ đè đúng vị trí khi ảnh chụp trùng với toàn màn hình
                val inPlace = shot.screenRect.width() == sw && shot.screenRect.height() == sh

                if (region != null && !inPlace) toast(getString(R.string.region_unsupported), long = true)
                val area = if (region != null && inPlace) {
                    Rect(region).takeIf { it.intersect(0, 0, full.width, full.height) }
                } else null
                if (region != null && inPlace && area == null) {
                    toast(getString(R.string.no_text))
                    return@launch
                }
                val input = if (area != null) {
                    Bitmap.createBitmap(full, area.left, area.top, area.width(), area.height())
                } else full

                // AI trên máy: mô hình đã tải chưa, có nhìn được ảnh không
                val aiOn = Prefs.useAi(this@ScreenTranslateService)
                val aiSpec = LocalAi.selected(this@ScreenTranslateService)
                val aiReady = aiOn && ModelManager.isReady(this@ScreenTranslateService, aiSpec)
                if (aiOn && !aiReady) toast(getString(R.string.ai_not_ready_google, aiSpec.name), long = true)
                val aiVision = aiReady && aiSpec.vision && Prefs.aiVision(this@ScreenTranslateService)

                val statusBar = if (inPlace && area == null) statusBarHeight() else 0
                val blocks = ocr.recognize(input, src) { lang ->
                    toast(getString(R.string.downloading_ocr_data, tessLangName(lang)), long = true)
                }.map { b ->
                    // Bỏ ký tự rác do OCR đọc nhầm hình ảnh, khoảng trắng giữa chữ Hán ("不 是")
                    val rect = if (area != null) Rect(b.rect).apply { offset(area.left, area.top) } else b.rect
                    b.copy(text = TextPrep.clean(b.text), rect = rect)
                }.filter { // bỏ đồng hồ/biểu tượng trên thanh trạng thái
                    it.rect.bottom > statusBar && ParagraphBuilder.isTranslatable(it.text)
                }
                // Ảnh cho AI: vùng chọn, toàn màn hình (nếu bật) hoặc khi OCR không thấy chữ nào
                val aiImage: AiImage? = if (aiVision &&
                    (area != null || blocks.isEmpty() || Prefs.aiVisionFull(this@ScreenTranslateService))
                ) {
                    withContext(Dispatchers.Default) {
                        AiImages.fromBitmap(input, if (area != null) 1_300_000 else 1_000_000)
                    }
                } else null
                if (input !== full) input.recycle()
                full.recycle()
                val report = ocr.lastReport
                Log.i(TAG, "ocr blocks=${blocks.size} inPlace=$inPlace shot=${shot.screenRect} report=$report")
                DebugInfo.lastOcr = report?.toString()
                if (blocks.isEmpty() && aiImage != null) {
                    // OCR không đọc được (chữ nghệ thuật, truyện tranh...): để AI tự đọc ảnh
                    readWithAi(aiImage, tgt, area ?: Rect(0, 0, sw, sh))
                    return@launch
                }
                if (blocks.isEmpty()) {
                    DebugInfo.lastBlocks = 0
                    DebugInfo.lastUnits = emptyList()
                    DebugInfo.lastError = "no text"
                    val unreadable = (report?.droppedGarbage ?: 0) > 0
                    toast(getString(if (unreadable) R.string.unreadable_text else R.string.no_text), long = unreadable)
                    return@launch
                }

                // Nhận diện ngôn ngữ TỪNG đoạn rồi mới quyết định dịch hay bỏ qua: nếu để Google tự
                // nhận diện cả lô, màn hình trộn tiếng Việt + tiếng Trung sẽ bị coi là tiếng Việt.
                // Chữ IN HOA toàn bộ (tiêu đề video) được dịch ở dạng chữ thường rồi in hoa lại bản dịch.
                val caps = blocks.map { TextPrep.isAllCaps(it.text) }
                val query = blocks.mapIndexed { i, b -> if (caps[i]) TextPrep.toSentenceCase(b.text) else b.text }
                val langs = query.map { detector.detect(it) }
                val actions = langs.map { LangRouting.decide(it, src, tgt) }
                val todo = blocks.indices.filter { actions[it] is LangRouting.Action.Translate }
                val results = arrayOfNulls<String>(blocks.size)
                var error: Exception? = null
                if (todo.isNotEmpty()) {
                    try {
                        val r = translateTexts(
                            todo.map { query[it] },
                            todo.map { (actions[it] as LangRouting.Action.Translate).sl },
                            tgt, aiReady, aiImage
                        )
                        todo.forEachIndexed { k, i -> results[i] = r[k]?.let { t -> if (caps[i]) t.uppercase() else t } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "translate failed", e)
                        error = e
                    }
                }
                val units = blocks.indices.map { i ->
                    DebugInfo.UnitInfo(blocks[i].text, langs[i], actionName(actions[i]), results[i])
                }
                units.forEach {
                    Log.i(TAG, "unit [${it.lang}/${it.action}] '${it.original.take(60)}' -> '${it.translated?.take(60)}'")
                }
                DebugInfo.lastUnits = units
                DebugInfo.lastBlocks = blocks.size
                DebugInfo.lastOriginals = blocks.map { it.text }
                DebugInfo.lastTranslations = results.map { it ?: "" }

                // Đoạn có bản dịch khác bản gốc mới được vẽ; đoạn giống hệt (số, tên riêng...) để nguyên.
                val shown = blocks.indices.mapNotNull { i ->
                    results[i]?.let { t -> TranslatedBlock(blocks[i].text, t, blocks[i].rect, blocks[i].lines) }
                }.filterNot { sameText(it.original, it.translated) }
                val failed = todo.count { results[it] == null }
                if (shown.isEmpty()) {
                    val allFailed = todo.isNotEmpty() && failed == todo.size
                    DebugInfo.lastError = if (allFailed) "translate failed: $error" else "nothing to translate"
                    toast(
                        if (allFailed) translateErrorMessage(error)
                        else getString(R.string.nothing_to_translate_detail, blocks.size, targetName(tgt)),
                        long = true
                    )
                    return@launch
                }
                DebugInfo.lastError = null
                DebugInfo.resultCount++
                Log.i(TAG, "result blocks=${shown.size} first='${shown.first().translated.take(80)}'")
                if (failed > 0) toast(getString(R.string.partial_failed, failed, todo.size), long = true)
                showResult(shown, area, inPlace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "translate failed", e)
                DebugInfo.lastError = e.toString()
                toast("Lỗi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setLoading(false)
                busy = false
                aiRunning = false
            }
        }
    }

    // ---------------------------------------------------------------- AI trên máy

    /** Dịch bằng AI trên máy (nếu bật và đã tải mô hình), Google Dịch cho phần còn lại. */
    private suspend fun translateTexts(
        texts: List<String>, sls: List<String>, tgt: String, useAi: Boolean, image: AiImage?,
    ): List<String?> {
        if (!useAi) {
            DebugInfo.lastEngine = "google"
            return GoogleTranslator.translateGrouped(texts, sls, tgt)
        }
        val fallback = Prefs.aiFallback(this)
        val out: MutableList<String?>
        try {
            aiRunning = true
            val ai = obtainAi()
            progressToast(getString(R.string.ai_translating))
            val t0 = SystemClock.uptimeMillis()
            val translator = AiTranslator(ai)
            setAiProgress(0, texts.size)
            out = translator.translate(texts, sls, tgt, image) { p -> setAiProgress(p.done, p.total) }
                .toMutableList()
            DebugInfo.lastEngine = "ai:${ai.spec.id} calls=${translator.lastCalls} " +
                "${SystemClock.uptimeMillis() - t0}ms image=${image != null} ${ai.lastStats}"
            Log.i(TAG, "AI ${DebugInfo.lastEngine}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI translate failed", e)
            if (!fallback) throw e
            toast(getString(R.string.ai_failed_fallback, e.message ?: e.javaClass.simpleName), long = true)
            DebugInfo.lastEngine = "google (ai failed: ${e.message})"
            return GoogleTranslator.translateGrouped(texts, sls, tgt)
        } finally {
            aiRunning = false
            hideProgressToast()
            setAiProgress(0, 0)
        }
        val missing = out.indices.filter { out[it] == null }
        if (missing.isNotEmpty() && fallback) {
            try {
                val r = GoogleTranslator.translateGrouped(missing.map { texts[it] }, missing.map { sls[it] }, tgt)
                missing.forEachIndexed { k, i -> out[i] = r[k] }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "google fallback failed: $e")
            }
        }
        return out
    }

    private suspend fun obtainAi(): NativeBackend {
        val spec = LocalAi.selected(this)
        if (!LocalAi.isLoaded(spec)) progressToast(getString(R.string.ai_loading, spec.name))
        return LocalAi.obtain(this)
    }

    /** OCR không thấy chữ: mô hình đọc ảnh tự tìm chữ, dịch và hiện dạng danh sách. */
    private suspend fun readWithAi(image: AiImage, tgt: String, rect: Rect) {
        try {
            aiRunning = true
            val ai = obtainAi()
            progressToast(getString(R.string.ai_reading_image))
            val pairs = AiTranslator(ai).readImage(image, tgt)
            hideProgressToast()
            DebugInfo.lastEngine = "ai-read:${ai.spec.id} ${ai.lastStats}"
            DebugInfo.lastOriginals = pairs.map { it.first }
            DebugInfo.lastTranslations = pairs.map { it.second }
            if (pairs.isEmpty()) {
                DebugInfo.lastError = "no text"
                toast(getString(R.string.no_text))
                return
            }
            DebugInfo.lastError = null
            DebugInfo.resultCount++
            showResult(pairs.map { TranslatedBlock(it.first, it.second, rect) }, null, inPlace = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI read image failed", e)
            DebugInfo.lastError = "ai read failed: $e"
            toast("Lỗi AI: ${e.message ?: e.javaClass.simpleName}", long = true)
        } finally {
            aiRunning = false
            hideProgressToast()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hệ thống sắp hết bộ nhớ: nhả mô hình AI (vài GB), lần dịch sau sẽ nạp lại
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            if (!busy) LocalAi.release()
        }
    }

    private fun actionName(a: LangRouting.Action): String = when (a) {
        is LangRouting.Action.Translate -> "translate:${a.sl}"
        LangRouting.Action.SkipTarget -> "skip-target"
        LangRouting.Action.SkipRomanized -> "skip-romanized"
    }

    private fun targetName(code: String): String =
        Languages.targets.firstOrNull { it.code == code }?.name ?: code

    private fun tessLangName(tessLang: String): String =
        TesseractEngine.googleCodeFor(tessLang)?.let { targetName(it) } ?: tessLang

    private fun translateErrorMessage(e: Exception?): String = when {
        e is GoogleTranslator.TranslateException && e.rateLimited -> getString(R.string.rate_limited)
        e is java.io.IOException -> getString(R.string.network_error)
        else -> "Lỗi: ${e?.message ?: "không dịch được"}"
    }

    private fun sameText(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return norm(a) == norm(b)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Ảnh toàn màu đen (ứng dụng được chia sẻ bị ẩn hoặc chặn chụp màn hình).
     * Quét dày (mỗi 3 hàng, mỗi 2 điểm) để không nhầm ứng dụng nền đen có chữ nhỏ là ảnh trống.
     */
    private fun isBlank(b: Bitmap): Boolean {
        val w = b.width
        val row = IntArray(w)
        var y = 0
        while (y < b.height) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                if (Color.red(c) > 8 || Color.green(c) > 8 || Color.blue(c) > 8) return false
                x += 2
            }
            y += 3
        }
        return true
    }

    private fun showResult(items: List<TranslatedBlock>, area: Rect?, inPlace: Boolean) {
        val style = OverlayStyle.load(this)
        val onClose = { removeOverlay() }
        val onCopy = {
            val text = items.joinToString("\n") { it.translated }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", text))
            toast(getString(R.string.copied))
        }
        val view = if (inPlace) {
            TranslationOverlayView(uiContext, items, area, style, onClose, onCopy)
        } else {
            TranslationListView(uiContext, items, style, onClose, onCopy)
        }
        showOverlay(view)
    }

    private var lastToast: Toast? = null

    /** Thông báo "AI đang nạp/dịch…": tự ẩn khi xong để không che bản dịch. */
    private var aiToast: Toast? = null

    private fun progressToast(msg: String) {
        mainHandler.post {
            lastToast?.cancel()
            val t = Toast.makeText(this, msg, Toast.LENGTH_LONG)
            lastToast = t
            aiToast = t
            t.show()
        }
    }

    private fun hideProgressToast() {
        mainHandler.post {
            aiToast?.let { t ->
                t.cancel()
                if (lastToast === t) lastToast = null
            }
            aiToast = null
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        mainHandler.post {
            lastToast?.cancel()
            lastToast = Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
                .also { it.show() }
        }
    }

    // ---------------------------------------------------------------- Cleanup

    override fun onDestroy() {
        stopping = true
        isRunning = false
        scope.cancel()
        removeOverlay()
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
        capturer?.release()
        capturer = null
        runCatching { projection?.stop() }
        projection = null
        ocr.close()
        detector.close()
        LocalAi.release()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
