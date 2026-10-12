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

    /** Giới hạn ký tự cho mỗi lần gửi gộp nhiều khối chữ. */
    private const val BATCH_CHARS = 3500

    class HttpException(val code: Int) : IOException("Google Translate HTTP $code")

    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return text
        var attempt = 0
        while (true) {
            try {
                return request(text, source, target)
            } catch (e: HttpException) {
                // 429/5xx: thử lại sau một chút
                if (attempt >= 2 || (e.code != 429 && e.code < 500)) throw e
            }
            attempt++
            delay(600L * attempt)
        }
    }

    private suspend fun request(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(text, "UTF-8")
            val params = "client=gtx&sl=${enc(source)}&tl=${enc(target)}&dt=t&ie=UTF-8&oe=UTF-8"
            val usePost = q.length > 1800
            val url = if (usePost) URL("$BASE?$params") else URL("$BASE?$params&q=$q")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", UA)
                if (usePost) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded;charset=UTF-8"
                    )
                    conn.outputStream.use { it.write("q=$q".toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code != 200) throw HttpException(code)
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(body)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Dịch nhiều khối chữ. Gộp các khối thành ít yêu cầu (mỗi khối một dòng) để tránh bị giới hạn
     * tần suất; nếu tách kết quả không khớp thì dịch riêng từng khối.
     * Ném lỗi nếu không dịch được khối nào (ví dụ mất mạng).
     */
    suspend fun translateAll(texts: List<String>, source: String, target: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        val clean = texts.map { it.replace('\n', ' ').trim() }
        val batches = ArrayList<IntRange>()
        var start = 0
        var len = 0
        for (i in clean.indices) {
            val l = clean[i].length + 1
            if (i > start && len + l > BATCH_CHARS) {
                batches.add(start until i); start = i; len = 0
            }
            len += l
        }
        batches.add(start until clean.size)

        val out = arrayOfNulls<String>(clean.size)
        var lastError: Exception? = null
        coroutineScope {
            val sem = Semaphore(3)
            batches.map { range ->
                async {
                    sem.withPermit {
                        val part = clean.subList(range.first, range.last + 1)
                        val joined = part.joinToString("\n")
                        val res = try {
                            translate(joined, source, target)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e; null
                        }
                        val lines = res?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        if (lines != null && lines.size == part.size) {
                            for (k in part.indices) out[range.first + k] = lines[k]
                        } else if (res != null) {
                            // Không tách được: dịch riêng từng khối
                            for (k in part.indices) {
                                out[range.first + k] = try {
                                    translate(part[k], source, target)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastError = e; null
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (out.all { it == null }) throw lastError ?: IOException("Translate failed")
        return clean.indices.map { out[it] ?: texts[it] }
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
