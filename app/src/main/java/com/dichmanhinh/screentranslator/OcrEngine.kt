package com.dichmanhinh.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

data class OcrBlock(val text: String, val rect: Rect, val lines: Int = 1)

/** Nhận dạng chữ trên ảnh chụp màn hình bằng ML Kit (chạy offline trên máy). */
class OcrEngine {
    private enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

    private val recognizers = HashMap<Script, TextRecognizer>()

    private fun recognizer(s: Script): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(s) {
            when (s) {
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun scriptFor(lang: String): Script? = when {
        lang == Languages.AUTO -> null
        lang.startsWith("zh") -> Script.CHINESE
        lang == "ja" -> Script.JAPANESE
        lang == "ko" -> Script.KOREAN
        lang in setOf("hi", "mr", "ne", "sa", "mai", "bho", "doi", "gom") -> Script.DEVANAGARI
        else -> Script.LATIN
    }

    suspend fun recognize(bitmap: Bitmap, sourceLang: String): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val fixed = scriptFor(sourceLang)
        if (fixed != null) return toBlocks(recognizer(fixed).process(image).await())

        // Tự động: chạy tất cả bộ nhận dạng, chọn kết quả phù hợp nhất với chữ trên màn hình.
        val results = coroutineScope {
            Script.values().map { s ->
                async {
                    s to try {
                        recognizer(s).process(image).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.toMap()

        var best: Script = Script.LATIN
        var bestScore = 0
        for ((s, text) in results) {
            if (s == Script.LATIN || text == null) continue
            val score = score(s, text.text)
            if (score > bestScore) {
                best = s; bestScore = score
            }
        }
        if (bestScore < 3) best = Script.LATIN
        val chosen = results[best] ?: results.values.firstOrNull { it != null } ?: return emptyList()
        return toBlocks(chosen)
    }

    private fun score(s: Script, text: String): Int {
        var n = 0
        for (ch in text) {
            val c = ch.code
            val han = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF
            val kana = c in 0x3040..0x30FF
            val hangul = c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F
            val deva = c in 0x0900..0x097F
            n += when (s) {
                Script.CHINESE -> if (han) 2 else 0
                Script.JAPANESE -> if (kana) 3 else if (han) 1 else 0
                Script.KOREAN -> if (hangul) 2 else 0
                Script.DEVANAGARI -> if (deva) 2 else 0
                Script.LATIN -> 0
            }
        }
        return n
    }

    private fun toBlocks(text: Text): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (block in text.textBlocks) {
            val box = block.boundingBox ?: continue
            val sb = StringBuilder()
            var lineCount = 0
            for (line in block.lines) {
                val t = line.text.trim()
                if (t.isEmpty()) continue
                lineCount++
                if (sb.isNotEmpty()) {
                    val last = sb[sb.length - 1]
                    if (!isCjk(last) && !isCjk(t[0])) sb.append(' ')
                }
                sb.append(t)
            }
            val s = sb.toString().trim()
            if (s.isNotEmpty() && s.any { it.isLetter() }) out.add(OcrBlock(s, Rect(box), lineCount.coerceAtLeast(1)))
        }
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xFF00..0xFFEF || c in 0x3000..0x303F
    }

    fun close() = synchronized(recognizers) {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
