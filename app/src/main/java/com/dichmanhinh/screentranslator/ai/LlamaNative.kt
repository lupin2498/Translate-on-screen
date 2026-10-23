package com.dichmanhinh.screentranslator.ai

/**
 * Hàm gốc (C++) của libscreenai.so – llama.cpp chạy mô hình GGUF ngay trên máy.
 * Chuỗi văn bản đi qua dưới dạng byte UTF-8 (xem screenai_jni.cpp).
 * Lớp này không dùng API Android để kiểm thử được trên JVM với thư viện dựng cho máy tính.
 */
object LlamaNative {

    /** Báo tiến độ sinh chữ; trả về false để dừng. */
    fun interface ProgressListener {
        fun onProgress(generatedTokens: Int): Boolean
    }

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /** Nạp thư viện của ứng dụng (Android). */
    @Synchronized
    fun loadLibrary(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("screenai")
            loaded = true
            true
        } catch (e: Throwable) {
            loadError = e.toString()
            false
        }
    }

    /** Nạp thư viện từ đường dẫn tuyệt đối (kiểm thử trên máy tính). */
    @Synchronized
    fun loadFrom(path: String): Boolean {
        if (loaded) return true
        return try {
            System.load(path)
            loaded = true
            true
        } catch (e: Throwable) {
            loadError = e.toString()
            false
        }
    }

    val isLoaded: Boolean get() = loaded

    /** Thiết bị có chạy được AI trên máy không (máy 32-bit: không). */
    fun supported(): Boolean = loaded && runCatching { nativeSupported() }.getOrDefault(false)

    @Volatile
    private var initialized: String? = null

    /** Khởi tạo backend (một lần). [backendDir] = thư mục chứa libggml-cpu-*.so (Android). */
    @Synchronized
    fun init(backendDir: String?): String {
        initialized?.let { return it }
        val info = String(nativeInit(backendDir), Charsets.UTF_8)
        initialized = info
        return info
    }

    fun lastError(): String = String(nativeLastError(), Charsets.UTF_8)

    fun mediaMarker(): String = String(nativeMediaMarker(), Charsets.UTF_8)

    fun load(model: String, mmproj: String?, nCtx: Int, nThreads: Int, imageMaxTokens: Int): Long =
        nativeLoad(model, mmproj, nCtx, nThreads, imageMaxTokens)

    fun info(handle: Long): String = String(nativeInfo(handle), Charsets.UTF_8)

    fun hasVision(handle: Long): Boolean = nativeHasVision(handle)

    /** Sinh văn bản; null nếu lỗi hoặc bị hủy (xem [lastError]). */
    fun generate(
        handle: Long, system: String?, user: String, image: AiImage?, maxTokens: Int,
        sampling: Sampling, listener: ProgressListener?
    ): String? {
        val out = nativeGenerate(
            handle,
            system?.toByteArray(Charsets.UTF_8),
            user.toByteArray(Charsets.UTF_8),
            image?.rgb, image?.width ?: 0, image?.height ?: 0,
            maxTokens, sampling.temperature, sampling.topK, sampling.topP, sampling.minP,
            sampling.repeatPenalty, listener
        ) ?: return null
        return String(out, Charsets.UTF_8)
    }

    /** [promptTokens, promptMs, genTokens, genMs, reusedTokens] của lần sinh gần nhất. */
    fun stats(handle: Long): IntArray? = nativeStats(handle)

    fun cancel(handle: Long) = nativeCancel(handle)

    fun free(handle: Long) = nativeFree(handle)

    private external fun nativeSupported(): Boolean
    private external fun nativeInit(backendDir: String?): ByteArray
    private external fun nativeLastError(): ByteArray
    private external fun nativeMediaMarker(): ByteArray
    private external fun nativeLoad(model: String, mmproj: String?, nCtx: Int, nThreads: Int, imageMaxTokens: Int): Long
    private external fun nativeInfo(handle: Long): ByteArray
    private external fun nativeHasVision(handle: Long): Boolean
    private external fun nativeGenerate(
        handle: Long, system: ByteArray?, user: ByteArray, rgb: ByteArray?, width: Int, height: Int,
        maxTokens: Int, temp: Float, topK: Int, topP: Float, minP: Float, repeat: Float,
        listener: ProgressListener?
    ): ByteArray?
    private external fun nativeStats(handle: Long): IntArray?
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
}
