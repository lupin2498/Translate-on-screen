package com.dichmanhinh.screentranslator

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Giữ phiên MediaProjection và luôn lưu khung hình mới nhất.
 *
 * Ở chế độ "chia sẻ một ứng dụng" (Android 14+), hệ thống chỉ gửi khung hình mới khi ứng dụng
 * được chia sẻ vẽ lại; nút nổi/lớp phủ của ta không nằm trong vùng chụp nên ẩn chúng không tạo
 * khung hình mới. Vì vậy phải giữ lại khung hình gần nhất thay vì chờ khung hình mới.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val handler: Handler,
) {
    companion object {
        private const val TAG = "ScreenTranslator"
        private const val MAX_IMAGES = 3
    }

    /** Kết quả chụp: ảnh theo tọa độ màn hình và vùng màn hình mà ảnh bao phủ. */
    class Shot(val bitmap: Bitmap, val screenRect: Rect)

    private val lock = Any()
    private var reader: ImageReader? = null
    @Volatile private var display: VirtualDisplay? = null
    private var latest: Image? = null
    @Volatile private var frameCount = 0L
    @Volatile var released = false
        private set

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile private var dpi = 0

    /** Kích thước nội dung được chụp (API 34+, khi chia sẻ một ứng dụng). 0 nếu chưa biết. */
    @Volatile var contentWidth = 0
        private set
    @Volatile var contentHeight = 0
        private set

    /** false khi ứng dụng được chia sẻ không còn hiển thị (người dùng chuyển sang app khác). */
    @Volatile var contentVisible = true
        private set

    private val listener = ImageReader.OnImageAvailableListener { r ->
        val img = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed", e); null
        } ?: return@OnImageAvailableListener
        synchronized(lock) {
            if (r !== reader || released) {
                img.close(); return@OnImageAvailableListener
            }
            latest?.close()
            latest = img
            frameCount++
        }
    }

    /**
     * API 34+: kích thước vùng được chụp thay đổi (bắt đầu chụp, xoay màn hình, chia đôi màn hình...).
     * Đổi kích thước VirtualDisplay cho khớp để nội dung không bị thu nhỏ/viền đen.
     * Gọi trên luồng [handler].
     */
    fun onContentResized(w: Int, h: Int) {
        Log.i(TAG, "captured content resized ${w}x$h (display ${width}x$height)")
        if (w <= 0 || h <= 0) return
        contentWidth = w
        contentHeight = h
        if (display != null) resize(w, h, dpi)
    }

    fun onContentVisibilityChanged(visible: Boolean) {
        Log.i(TAG, "captured content visible=$visible")
        contentVisible = visible
    }

    /** Tạo VirtualDisplay. Chỉ được gọi một lần cho mỗi phiên MediaProjection (Android 14+). */
    fun start(w: Int, h: Int, densityDpi: Int): Boolean {
        width = w; height = h; dpi = densityDpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        r.setOnImageAvailableListener(listener, handler)
        synchronized(lock) { reader = r }
        display = try {
            projection.createVirtualDisplay(
                "ScreenTranslator", w, h, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e); null
        }
        if (display == null) return false
        // onCapturedContentResize có thể tới trước khi display được gán
        handler.post {
            val cw = contentWidth
            val ch = contentHeight
            if (cw > 0 && ch > 0) resize(cw, ch, dpi)
        }
        return true
    }

    /** Đổi kích thước (xoay màn hình / nội dung đổi kích thước). Trả về true nếu có thay đổi. */
    @Synchronized
    fun resize(w: Int, h: Int, densityDpi: Int): Boolean {
        val vd = display ?: return false
        if (released || (w == width && h == height)) return false
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        r.setOnImageAvailableListener(listener, handler)
        val old: ImageReader?
        synchronized(lock) {
            old = reader
            reader = r
            latest?.close()
            latest = null
        }
        vd.resize(w, h, densityDpi)
        vd.surface = r.surface
        old?.close()
        width = w; height = h; dpi = densityDpi
        return true
    }

    val frames: Long get() = frameCount

    /** Đã có khung hình nào chưa. */
    val hasFrame: Boolean get() = synchronized(lock) { latest != null }

    /** Nội dung được chụp trùng toàn màn hình (toàn bộ màn hình hoặc ứng dụng toàn màn hình). */
    fun coversScreen(screenW: Int, screenH: Int): Boolean {
        val cw = contentWidth
        val ch = contentHeight
        if (cw <= 0 || ch <= 0) return true
        return cw == screenW && ch == screenH
    }

    /**
     * Chờ khung hình mới (sau khi đã ẩn nút nổi) tối đa [timeoutMs]; nếu không có (nội dung đứng
     * yên, hoặc đang chia sẻ một ứng dụng) thì dùng khung hình gần nhất.
     */
    suspend fun capture(sinceFrame: Long, timeoutMs: Long = 400): Shot? {
        var waited = 0L
        while (frameCount <= sinceFrame && waited < timeoutMs) {
            delay(25); waited += 25
        }
        if (frameCount > sinceFrame) {
            // Cho hệ thống vẽ xong trạng thái mới (nút nổi đã ẩn hẳn)
            val target = frameCount
            var settle = 0L
            while (settle < 120 && frameCount == target) {
                delay(20); settle += 20
            }
        }
        return withContext(Dispatchers.Default) {
            val bmp = synchronized(lock) { latest?.let { toBitmap(it) } }
            bmp?.let { mapToScreen(it) }
        }
    }

    private fun toBitmap(image: Image): Bitmap? = try {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        // duplicate(): cùng một Image có thể được chuyển đổi nhiều lần
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val raw = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        if (raw.width == w) raw else Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "image -> bitmap failed", e); null
    }

    /**
     * Khi chia sẻ một ứng dụng, nội dung ứng dụng được thu nhỏ vừa khít (giữ tỉ lệ) và căn giữa
     * trong VirtualDisplay. Cắt phần nội dung thật và phóng về kích thước gốc để tọa độ OCR
     * trùng với tọa độ trên màn hình.
     */
    private fun mapToScreen(bmp: Bitmap): Shot {
        val cw = contentWidth
        val ch = contentHeight
        if (cw <= 0 || ch <= 0 || (cw == bmp.width && ch == bmp.height)) {
            return Shot(bmp, Rect(0, 0, bmp.width, bmp.height))
        }
        val scale = min(bmp.width.toFloat() / cw, bmp.height.toFloat() / ch)
        val dw = (cw * scale).roundToInt().coerceIn(1, bmp.width)
        val dh = (ch * scale).roundToInt().coerceIn(1, bmp.height)
        val left = ((bmp.width - dw) / 2).coerceAtLeast(0)
        val top = ((bmp.height - dh) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bmp, left, top, dw, dh)
        val out = if (dw == cw && dh == ch) cropped
        else Bitmap.createScaledBitmap(cropped, cw, ch, true).also { if (it !== cropped) cropped.recycle() }
        if (out !== bmp) bmp.recycle()
        // Ứng dụng toàn màn hình: nội dung trùng màn hình từ góc (0,0).
        // Chia đôi màn hình/cửa sổ nổi: không biết vị trí chính xác, căn theo góc trên-trái.
        return Shot(out, Rect(0, 0, cw, ch))
    }

    @Synchronized
    fun release() {
        synchronized(lock) {
            released = true
            latest?.close()
            latest = null
        }
        runCatching { display?.release() }
        display = null
        synchronized(lock) {
            runCatching { reader?.close() }
            reader = null
        }
    }
}
