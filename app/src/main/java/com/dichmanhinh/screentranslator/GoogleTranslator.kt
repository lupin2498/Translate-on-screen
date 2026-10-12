package com.dichmanhinh.screentranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dịch văn bản qua Google Translate (endpoint translate.googleapis.com). */
object GoogleTranslator {
    private const val BASE = "https://translate.googleapis.com/translate_a/single"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext text
            val q = URLEncoder.encode(text, "UTF-8")
            val params = "client=gtx&sl=${enc(source)}&tl=${enc(target)}&dt=t&ie=UTF-8&oe=UTF-8"
            val usePost = q.length > 5000
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
                    conn.outputStream.use { it.write("q=$q".toByteArray()) }
                }
                val code = conn.responseCode
                if (code != 200) throw RuntimeException("Google Translate HTTP $code")
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(body)
            } finally {
                conn.disconnect()
            }
        }

    /** Dịch nhiều đoạn song song; đoạn lỗi sẽ giữ nguyên văn bản gốc. */
    suspend fun translateAll(texts: List<String>, source: String, target: String): List<String> =
        coroutineScope {
            val sem = Semaphore(6)
            texts.map { t ->
                async {
                    sem.withPermit {
                        runCatching { translate(t, source, target) }.getOrElse { t }
                    }
                }
            }.awaitAll()
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parse(body: String): String {
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
