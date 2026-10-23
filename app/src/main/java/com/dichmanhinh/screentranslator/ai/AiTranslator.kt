package com.dichmanhinh.screentranslator.ai

import kotlinx.coroutines.CancellationException

/** Mô hình ngôn ngữ đang được nạp (thật: [NativeBackend]; kiểm thử: giả lập). */
interface LlmBackend {
    val style: PromptStyle
    val hasVision: Boolean
    val mediaMarker: String
    val sampling: Sampling
    /** Số token tối đa của ngữ cảnh. */
    val contextSize: Int

    /** Sinh câu trả lời; ném [AiException] nếu lỗi. */
    suspend fun generate(system: String?, user: String, image: AiImage?, maxTokens: Int): String
}

class AiException(message: String) : Exception(message)

/**
 * Dịch các đoạn chữ trên màn hình bằng mô hình chạy trên máy.
 *
 * - Cả màn hình được dịch trong ít lần gọi nhất (mỗi đoạn bọc thẻ <sN>) để mô hình hiểu ngữ cảnh:
 *   phụ đề nối tiếp nhau, xưng hô, thuật ngữ thống nhất.
 * - Mô hình đa năng được dặn sửa lỗi OCR; nếu nhìn được ảnh thì đọc lại chữ trên ảnh chụp.
 * - Đoạn nào mô hình bỏ sót hoặc dịch hỏng thì dịch lại riêng (kèm chữ xung quanh làm ngữ cảnh).
 */
class AiTranslator(
    private val llm: LlmBackend,
    /** true: dịch từng đoạn riêng (kèm chữ xung quanh làm ngữ cảnh) thay vì cả màn hình một lần. */
    private val perSegment: Boolean = false,
) {

    data class Progress(val done: Int, val total: Int)

    /** Thống kê lần dịch gần nhất (hiển thị/ghi log). */
    var lastCalls = 0
        private set

    /**
     * @param sources mã ngôn ngữ nguồn của từng đoạn ("auto" nếu không rõ)
     * @param image ảnh chụp (vùng) màn hình – chỉ dùng khi mô hình nhìn được ảnh
     * @return bản dịch từng đoạn; null = không dịch được đoạn đó
     */
    suspend fun translate(
        segments: List<String>, sources: List<String>, target: String, image: AiImage? = null,
        onProgress: (Progress) -> Unit = {},
    ): List<String?> {
        require(segments.size == sources.size)
        lastCalls = 0
        val out = arrayOfNulls<String>(segments.size)
        if (segments.isEmpty()) return out.toList()
        val clean = segments.map { AiPrompts.oneLine(it) }
        val useImage = image != null && llm.hasVision && llm.style == PromptStyle.GENERAL

        // Ngân sách token cho phần chữ nguồn của một lô (còn chỗ cho bản dịch dài hơn ~2.5 lần)
        val imageTokens = if (useImage) 1200 else 0
        val budget = ((llm.contextSize - 900 - imageTokens) / 3.6f).toInt().coerceIn(200, 1400)
        // Hunyuan-MT là mô hình chuyên dịch từng đoạn: luôn dịch riêng từng đoạn (xem AiPrompts.hyMtSingle)
        val single = perSegment || llm.style == PromptStyle.HY_MT
        val batches = if (single) clean.indices.map { listOf(it) } else AiPrompts.packBatches(clean, budget)
        var done = 0
        var lastError: Exception? = null
        for ((bi, idx) in batches.withIndex()) {
            val texts = idx.map { clean[it] }
            try {
                val ctx = when {
                    llm.style == PromptStyle.HY_MT -> null
                    single -> neighbors(clean, idx[0])
                    else -> contextFor(clean, idx)
                }
                val res = translateBatch(texts, idx.map { sources[it] }, target,
                    if (useImage && bi == 0) image else null, ctx)
                idx.forEachIndexed { k, i -> out[i] = res[k] }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            done += idx.size
            onProgress(Progress(done, segments.size))
        }

        // Dịch lại riêng các đoạn mô hình đa năng bỏ sót (HY-MT vốn đã dịch từng đoạn)
        val missing = out.indices.filter { out[it] == null }
        if (llm.style == PromptStyle.GENERAL && missing.isNotEmpty() && missing.size <= 12) {
            for (i in missing) {
                try {
                    out[i] = translateOne(clean[i], sources[i], target, neighbors(clean, i))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        if (out.all { it == null }) throw lastError ?: AiException("AI không trả về bản dịch")
        return out.toList()
    }

    private suspend fun translateBatch(
        texts: List<String>, sources: List<String>, target: String, image: AiImage?, context: String?,
    ): Array<String?> {
        if (llm.style == PromptStyle.HY_MT) {
            return Array(texts.size) { translateOne(texts[it], sources[it], target, null) }
        }
        val raw = call(
            AiPrompts.generalSystem(target, image != null),
            AiPrompts.generalUser(texts, sources, target, if (image != null) llm.mediaMarker else null, context),
            image, AiPrompts.maxOutputTokens(texts)
        )
        val res = AiPrompts.parseTagged(raw, texts.size)
        for (k in res.indices) {
            val t = res[k] ?: continue
            if (AiPrompts.looksBroken(texts[k], t)) res[k] = null
        }
        return res
    }

    private suspend fun translateOne(text: String, source: String, target: String, context: String?): String? {
        val maxTokens = AiPrompts.maxOutputTokens(listOf(text))
        val raw = when (llm.style) {
            PromptStyle.HY_MT -> call(null, AiPrompts.hyMtSingle(text, source, target), null, maxTokens)
            PromptStyle.GENERAL -> call(
                AiPrompts.generalSystem(target, false),
                AiPrompts.generalUser(listOf(text), listOf(source), target, null, context),
                null, maxTokens
            )
        }
        val t = AiPrompts.parseSingle(raw) ?: return null
        return t.takeUnless { AiPrompts.looksBroken(text, it) }
    }

    /**
     * Khi OCR không thấy chữ: mô hình tự đọc ảnh và dịch.
     * @return danh sách (chữ gốc, bản dịch)
     */
    suspend fun readImage(image: AiImage, target: String): List<Pair<String, String>> {
        if (!llm.hasVision) throw AiException("Mô hình không đọc được ảnh")
        val raw = call(
            AiPrompts.readImageSystem(target), AiPrompts.readImageUser(llm.mediaMarker, target), image, 1536
        )
        return AiPrompts.parseReadImage(raw).filterNot { AiPrompts.looksBroken(it.first.ifEmpty { "x" }, it.second) }
    }

    private suspend fun call(system: String?, user: String, image: AiImage?, maxTokens: Int): String {
        lastCalls++
        return llm.generate(system, user, image, maxTokens)
    }

    /** Chữ của các lô khác trên màn hình (rút gọn) làm ngữ cảnh khi phải chia nhiều lô. */
    private fun contextFor(all: List<String>, batch: List<Int>): String? {
        if (batch.size == all.size) return null
        val first = batch.first()
        val before = all.subList(0, first).takeLast(4)
        val after = all.subList(batch.last() + 1, all.size).take(2)
        return (before + after).joinToString("\n").take(500).ifBlank { null }
    }

    private fun neighbors(all: List<String>, i: Int): String? {
        val ctx = (all.subList(maxOf(0, i - 2), i) + all.subList(minOf(all.size, i + 1), minOf(all.size, i + 2)))
        return ctx.joinToString("\n").take(300).ifBlank { null }
    }
}
