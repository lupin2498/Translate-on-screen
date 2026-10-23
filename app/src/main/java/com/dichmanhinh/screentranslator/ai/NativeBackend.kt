package com.dichmanhinh.screentranslator.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Một mô hình đã nạp vào bộ nhớ qua llama.cpp. Không dùng API Android (kiểm thử được trên JVM).
 * Hủy coroutine đang chờ sẽ dừng ngay việc tính toán trong C++.
 */
class NativeBackend private constructor(
    val spec: ModelSpec,
    private var handle: Long,
    override val hasVision: Boolean,
    override val contextSize: Int,
    val info: String,
) : LlmBackend, Closeable {

    override val style: PromptStyle get() = spec.style
    override val sampling: Sampling get() = spec.sampling
    override val mediaMarker: String = LlamaNative.mediaMarker()

    private val mutex = Mutex()

    /** Thống kê lần sinh gần nhất. */
    @Volatile
    var lastStats: Stats? = null
        private set

    data class Stats(val promptTokens: Int, val promptMs: Int, val genTokens: Int, val genMs: Int, val reused: Int) {
        val genSpeed: Float get() = if (genMs > 0) genTokens * 1000f / genMs else 0f
        val promptSpeed: Float get() = if (promptMs > 0) (promptTokens - reused) * 1000f / promptMs else 0f
        override fun toString(): String =
            "prompt $promptTokens tok (dùng lại $reused) ${promptMs}ms, sinh $genTokens tok ${genMs}ms " +
                "(%.1f tok/s)".format(genSpeed)
    }

    override suspend fun generate(system: String?, user: String, image: AiImage?, maxTokens: Int): String =
        mutex.withLock {
            val h = handle
            if (h == 0L) throw AiException("Mô hình đã được giải phóng")
            coroutineScope {
                val finished = AtomicBoolean(false)
                // Coroutine canh: bị hủy -> báo C++ dừng (kể cả đang xử lý prompt dài hoặc ảnh)
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) LlamaNative.cancel(h)
                    }
                }
                try {
                    val res = withContext(Dispatchers.Default) {
                        LlamaNative.generate(h, system, user, if (hasVision) image else null, maxTokens, sampling, null)
                    }
                    finished.set(true)
                    LlamaNative.stats(h)?.let { s ->
                        if (s.size >= 5) lastStats = Stats(s[0], s[1], s[2], s[3], s[4])
                    }
                    res ?: throw AiException(LlamaNative.lastError().ifEmpty { "Lỗi không rõ" })
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
        }

    /** Dừng lần sinh đang chạy (gọi từ luồng bất kỳ). */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) LlamaNative.free(h)
    }

    companion object {
        /**
         * Nạp mô hình (chậm: vài giây – gọi ngoài luồng chính).
         * @param modelPath đường dẫn file GGUF; [mmprojPath] = bộ chiếu ảnh (có thể null)
         */
        fun load(
            spec: ModelSpec, modelPath: String, mmprojPath: String?, threads: Int, backendDir: String?,
            imageMaxTokens: Int = 560,
        ): NativeBackend {
            if (!LlamaNative.isLoaded && !LlamaNative.loadLibrary()) {
                throw AiException("Không nạp được thư viện AI: ${LlamaNative.loadError}")
            }
            if (!LlamaNative.supported()) throw AiException("Máy này (32-bit) không chạy được AI trên máy")
            LlamaNative.init(backendDir)
            val h = LlamaNative.load(modelPath, mmprojPath, spec.nCtx, threads, imageMaxTokens)
            if (h == 0L) throw AiException(LlamaNative.lastError().ifEmpty { "Không nạp được mô hình" })
            val info = LlamaNative.info(h)
            val ctx = Regex("ctx=(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: spec.nCtx
            return NativeBackend(spec, h, LlamaNative.hasVision(h), ctx, info)
        }
    }
}
