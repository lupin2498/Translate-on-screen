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

    /** Nạp sẵn mô hình OCR để lần dịch đầu tiên nhanh hơn. */
    suspend fun warmUp(sourceLang: String) {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        try {
            recognize(bmp, sourceLang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // bỏ qua: chỉ là nạp sẵn
        } finally {
            bmp.recycle()
        }
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

    /** Lấy từng dòng OCR rồi ghép thành đoạn văn để dịch trọn câu. */
    private fun toBlocks(text: Text): List<OcrBlock> {
        val lines = ArrayList<TextLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isNotEmpty()) lines.add(TextLine(t, box.left, box.top, box.right, box.bottom))
            }
        }
        return ParagraphBuilder.build(lines)
            .filter { ParagraphBuilder.isTranslatable(it.text) }
            .map { OcrBlock(it.text, Rect(it.left, it.top, it.right, it.bottom), it.lines) }
    }

    fun close() = synchronized(recognizers) {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
