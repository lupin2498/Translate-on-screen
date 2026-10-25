package com.dichmanhinh.screentranslator.ai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.dichmanhinh.screentranslator.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Giữ mô hình AI đang dùng trong bộ nhớ (một mô hình mỗi lúc) cho cả dịch vụ dịch màn hình lẫn
 * màn hình cài đặt. Nạp mô hình mất vài giây nên chỉ nạp một lần, giải phóng khi dừng dịch.
 */
object LocalAi {
    private const val TAG = "ScreenAI"
    private val mutex = Mutex()

    @Volatile
    private var backend: NativeBackend? = null

    val loaded: NativeBackend? get() = backend

    /** Mô hình người dùng đã chọn (kể cả chưa tải). */
    fun selected(ctx: Context): ModelSpec =
        ModelManager.find(ctx, Prefs.aiModel(ctx)) ?: ModelCatalog.HY_MT

    /** Mô hình đã chọn đã tải xong chưa. */
    fun isReady(ctx: Context): Boolean = ModelManager.isReady(ctx, selected(ctx))

    fun isLoaded(spec: ModelSpec): Boolean = backend?.spec?.id == spec.id

    /** Nạp (nếu cần) mô hình đang chọn. Chậm ở lần đầu – vài giây. */
    suspend fun obtain(ctx: Context): NativeBackend = mutex.withLock {
        val spec = selected(ctx)
        val paths = ModelManager.paths(ctx, spec) ?: throw AiException("Chưa tải mô hình ${spec.name}")
        // Dùng lại mô hình đang nạp; nạp lại nếu vừa có thêm file đọc ảnh (mmproj)
        backend?.let { if (it.spec.id == spec.id && loadedMmproj == paths.second) return@withLock it }
        backend?.close()
        backend = null
        val app = ctx.applicationContext
        val b = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            NativeBackend.load(
                spec, paths.first, paths.second, threads(), app.applicationInfo.nativeLibraryDir
            ).also {
                Log.i(TAG, "loaded ${spec.id} in ${System.currentTimeMillis() - t0}ms: ${it.info}")
            }
        }
        backend = b
        loadedMmproj = paths.second
        b
    }

    /** mmproj dùng khi nạp mô hình hiện tại (null = không có). */
    @Volatile
    private var loadedMmproj: String? = null

    fun cancel() {
        backend?.cancel()
    }

    /** Giải phóng bộ nhớ của mô hình. */
    fun release() {
        val b = backend
        backend = null
        if (b != null) {
            b.cancel()
            // chờ lượt sinh đang chạy (nếu có) kết thúc trong C++ trước khi giải phóng
            Thread { b.close() }.start()
        }
    }

    // ---------------------------------------------------------------- thiết bị

    /** Tổng RAM của máy (GB). */
    fun totalRamGb(ctx: Context): Float {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0f
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824f
    }

    fun is64Bit(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    /**
     * Số luồng tính toán: số nhân "lớn" (tần số tối đa ≥ 70% nhân nhanh nhất), trong khoảng 2..6.
     * Dùng cả nhân tiết kiệm điện thường làm chậm hơn vì các luồng phải chờ nhau.
     */
    fun threads(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        val big = if (freqs.size == n && freqs.isNotEmpty()) {
            val top = freqs.max()
            freqs.count { it >= top * 0.7 }
        } else n / 2
        return big.coerceIn(2, 6)
    }
}

/** Chuyển ảnh chụp màn hình thành ảnh RGB cho mô hình, thu nhỏ nếu quá [maxPixels] điểm ảnh. */
object AiImages {
    fun fromBitmap(src: Bitmap, maxPixels: Int): AiImage {
        val px = src.width.toLong() * src.height
        val scale = if (px > maxPixels) sqrt(maxPixels.toDouble() / px) else 1.0
        val w = (src.width * scale).toInt().coerceAtLeast(16)
        val h = (src.height * scale).toInt().coerceAtLeast(16)
        val bmp = if (scale < 1.0) Bitmap.createScaledBitmap(src, w, h, true) else src
        try {
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            var j = 0
            for (p in pixels) {
                rgb[j++] = (p shr 16 and 0xFF).toByte()
                rgb[j++] = (p shr 8 and 0xFF).toByte()
                rgb[j++] = (p and 0xFF).toByte()
            }
            return AiImage(rgb, w, h)
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }
}
