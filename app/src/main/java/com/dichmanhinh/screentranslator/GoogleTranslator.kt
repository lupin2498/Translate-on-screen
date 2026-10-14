package com.dichmanhinh.screentranslator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dịch văn bản qua Google Translate (endpoint translate.googleapis.com). */
object GoogleTranslator {
    private const val BASE = "https://translate.googleapis.com/translate_a/single"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /**
     * Độ dài tối đa (sau khi mã hóa URL) của tham số q trong một yêu cầu GET.
     * Không dùng POST: Google hay chuyển POST sang trang chặn "sorry" (nghi lưu lượng tự động).
     */
    const val MAX_Q = 1800

    class TranslateException(message: String, val rateLimited: Boolean = false) : IOException(message)

    /** Dịch một đoạn văn bản (tự chia nhỏ theo câu nếu quá dài). */
    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return text
        val parts = splitForRequest(text, MAX_Q)
        val sep = if (LangRouting.base(target) in setOf("zh", "ja")) "" else " "
        val out = ArrayList<String>(parts.size)
        for (p in parts) out.add(requestWithRetry(p, source, target).trim())
        return out.joinToString(sep)
    }

    /**
     * Dịch nhiều đoạn, mỗi đoạn với ngôn ngữ nguồn riêng ([sources]). Các đoạn cùng ngôn ngữ được
     * gộp thành ít yêu cầu (mỗi đoạn một dòng). Kết quả null = đoạn đó dịch lỗi.
     */
    suspend fun translateGrouped(texts: List<String>, sources: List<String>, target: String): List<String?> {
        require(texts.size == sources.size)
        val out = arrayOfNulls<String>(texts.size)
        val clean = texts.map { it.replace('\n', ' ').trim() }
        val jobs = ArrayList<Pair<String, List<Int>>>()
        clean.indices.groupBy { sources[it] }.forEach { (sl, idx) ->
            packBatches(idx, clean, MAX_Q).forEach { jobs.add(sl to it) }
        }
        var lastError: Exception? = null
        coroutineScope {
            val sem = Semaphore(3)
            jobs.map { (sl, idx) ->
                async {
                    sem.withPermit {
                        try {
                            if (idx.size == 1) {
                                out[idx[0]] = translate(clean[idx[0]], sl, target)
                            } else {
                                val res = requestWithRetry(idx.joinToString("\n") { clean[it] }, sl, target)
                                val lines = res.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                                if (lines.size == idx.size) {
                                    idx.forEachIndexed { k, i -> out[i] = lines[k] }
                                } else {
                                    // Không tách được kết quả theo dòng: dịch riêng từng đoạn
                                    for (i in idx) out[i] = translate(clean[i], sl, target)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                }
            }.awaitAll()
        }
        if (texts.isNotEmpty() && out.all { it == null }) {
            throw lastError ?: TranslateException("Translate failed")
        }
        return out.toList()
    }

    /** Gom các đoạn (theo thứ tự) thành lô sao cho q mã hóa không vượt quá [maxEncoded]. */
    fun packBatches(indices: List<Int>, texts: List<String>, maxEncoded: Int): List<List<Int>> {
        val batches = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var len = 0
        for (i in indices) {
            val l = enc(texts[i]).length + 3 // "%0A"
            if (cur.isNotEmpty() && len + l > maxEncoded) {
                batches.add(cur); cur = ArrayList(); len = 0
            }
            cur.add(i); len += l
        }
        if (cur.isNotEmpty()) batches.add(cur)
        return batches
    }

    /** Chia văn bản dài thành các phần (ưu tiên ranh giới câu) có q mã hóa ≤ [maxEncoded]. */
    fun splitForRequest(text: String, maxEncoded: Int): List<String> {
        if (enc(text).length <= maxEncoded) return listOf(text)
        val sentences = Regex("(?<=[.!?…。！？])\\s*").split(text).filter { it.isNotBlank() }
        val pieces = sentences.flatMap { s ->
            if (enc(s).length <= maxEncoded) listOf(s) else hardSplit(s, maxEncoded)
        }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in pieces) {
            val candidate = if (sb.isEmpty()) p else "$sb $p"
            if (sb.isNotEmpty() && enc(candidate).length > maxEncoded) {
                out.add(sb.toString()); sb.setLength(0); sb.append(p)
            } else {
                sb.setLength(0); sb.append(candidate)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Cắt câu quá dài tại khoảng trắng (hoặc theo ký tự nếu không có khoảng trắng). */
    private fun hardSplit(s: String, maxEncoded: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s.trim()
        while (rest.isNotEmpty() && enc(rest).length > maxEncoded) {
            var cut = rest.length
            while (cut > 1 && enc(rest.substring(0, cut)).length > maxEncoded) cut = cut * 3 / 4
            val space = rest.lastIndexOf(' ', cut)
            if (space > cut / 2) cut = space
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private suspend fun requestWithRetry(q: String, source: String, target: String): String {
        var attempt = 0
        while (true) {
            try {
                return request(q, source, target)
            } catch (e: TranslateException) {
                // 429/5xx: thử lại sau một chút. Trang chặn (302 -> sorry) thì không thử lại.
                if (attempt >= 2 || !e.message.orEmpty().let { it.contains("429") || it.contains(" 5") }) throw e
            }
            attempt++
            delay(700L * attempt)
        }
    }

    private suspend fun request(q: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val url = URL(
                "$BASE?client=gtx&sl=${enc(source)}&tl=${enc(target)}&dt=t&ie=UTF-8&oe=UTF-8&q=${enc(q)}"
            )
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", UA)
                val code = conn.responseCode
                when {
                    code == 200 -> Unit
                    code == 429 || code in 300..399 ->
                        throw TranslateException("Google Translate HTTP $code", rateLimited = true)
                    else -> throw TranslateException("Google Translate HTTP $code")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                try {
                    parse(body)
                } catch (e: Exception) {
                    throw TranslateException("Google Translate: phản hồi không hợp lệ", rateLimited = true)
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    internal fun parse(body: String): String {
        val root = JSONArray(body)
        val parts = root.optJSONArray(0) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val seg = parts.optJSONArray(i) ?: continue
            val s = seg.opt(0)
            if (s is String) sb.append(s)
        }
        return sb.toString()
    }
}
