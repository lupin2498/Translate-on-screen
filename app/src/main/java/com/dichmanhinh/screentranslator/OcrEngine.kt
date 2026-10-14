package com.dichmanhinh.screentranslator

import android.content.Context
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
import kotlin.math.max
import kotlin.math.min

data class OcrBlock(val text: String, val rect: Rect, val lines: Int = 1)

/**
 * Nhận dạng chữ trên ảnh chụp màn hình.
 * - ML Kit (offline): La-tinh, Trung, Nhật, Hàn, Devanagari. Chữ của từng bộ chữ được lấy từ bộ
 *   nhận dạng chuyên cho bộ chữ đó, phần chữ La-tinh lấy từ bộ La-tinh (đọc dấu tiếng Việt tốt hơn).
 * - Tesseract (tải dữ liệu lần đầu): Kirin, Hy Lạp, Ả Rập, Thái... – các bộ chữ ML Kit không có.
 *   Ở chế độ tự động, khi phát hiện chữ Kirin bị đọc nhầm thành La-tinh thì đọc lại bằng Tesseract.
 */
class OcrEngine(context: Context, private val detector: LanguageDetector) {
    private enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

    /** Tóm tắt lần OCR gần nhất (hiển thị thông báo, kiểm thử). */
    data class Report(
        val primary: String,
        val misreadLines: Int,
        val tesseractLang: String?,
        val tesseractLines: Int,
        val droppedGarbage: Int,
    )

    @Volatile
    var lastReport: Report? = null
        private set

    private val tess = TesseractEngine(context)
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

    /** Bộ chữ phải dùng khi người dùng chọn cố định ngôn ngữ nguồn (null = không ép). */
    private fun forcedScript(lang: String): Script? = when {
        lang.startsWith("zh") -> Script.CHINESE
        lang == "ja" -> Script.JAPANESE
        lang == "ko" -> Script.KOREAN
        lang in setOf("hi", "mr", "ne", "sa", "mai", "bho", "doi", "gom") -> Script.DEVANAGARI
        else -> null
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

    /**
     * @param onDownload được gọi khi cần tải dữ liệu Tesseract (lần đầu dùng một ngôn ngữ).
     */
    suspend fun recognize(
        bitmap: Bitmap, sourceLang: String, onDownload: (String) -> Unit = {}
    ): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val tessLang = TesseractEngine.tessLangFor(sourceLang)
        val forced = forcedScript(sourceLang)
        val scripts = when {
            forced != null -> listOf(Script.LATIN, forced)
            tessLang != null -> listOf(Script.LATIN)
            else -> Script.values().toList() // tự động hoặc ngôn ngữ chữ La-tinh: dò mọi bộ chữ
        }
        val results: Map<Script, Text?> = coroutineScope {
            scripts.map { s ->
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

        val latin = linesOf(results[Script.LATIN])
        val primary = forced ?: bestScript(results)
        var lines = if (primary != null) mergeScripts(linesOf(results[primary]), primary, latin) else latin

        // Chữ Kirin bị ML Kit đọc thành chữ La-tinh rác -> đọc lại bằng Tesseract
        val misread = lines.filter { OcrQuality.isMisreadLine(it.text) }
        val auto = sourceLang == Languages.AUTO
        val useTessLang = tessLang ?: if (auto && misread.isNotEmpty()) "rus" else null
        var tessCount = 0
        if (useTessLang != null) {
            val region = if (tessLang != null) null else regionAround(misread, bitmap.width, bitmap.height)
            val tLines = tess.recognize(bitmap, useTessLang, region, onDownload)
                .filter { it.confidence >= 40f && scriptMatches(it.line.text, useTessLang) }
                .map { it.line }
            val before = lines.size
            lines = arbitrate(lines, tLines)
            tessCount = lines.count { l -> tLines.any { it === l } }
            android.util.Log.i(
                "ScreenTranslator",
                "tesseract[$useTessLang] lines=${tLines.size} accepted=$tessCount (ml kit lines before=$before)"
            )
        }
        // Không bao giờ dịch chữ rác còn sót lại
        val garbage = lines.count { OcrQuality.isMisreadLine(it.text) }
        lines = lines.filterNot { OcrQuality.isMisreadLine(it.text) }

        lastReport = Report(
            primary = (primary ?: Script.LATIN).name,
            misreadLines = misread.size,
            tesseractLang = useTessLang,
            tesseractLines = tessCount,
            droppedGarbage = garbage,
        )
        return ParagraphBuilder.build(lines)
            .filter { ParagraphBuilder.isTranslatable(it.text) }
            .map { OcrBlock(it.text, Rect(it.left, it.top, it.right, it.bottom), it.lines) }
    }

    private fun linesOf(text: Text?): List<TextLine> {
        val out = ArrayList<TextLine>()
        text ?: return out
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isNotEmpty()) out.add(TextLine(t, box.left, box.top, box.right, box.bottom))
            }
        }
        return out
    }

    /** Bộ chữ không phải La-tinh xuất hiện nhiều nhất (null nếu gần như không có). */
    private fun bestScript(results: Map<Script, Text?>): Script? {
        var best: Script? = null
        var bestScore = 2
        for ((s, text) in results) {
            if (s == Script.LATIN || text == null) continue
            val score = text.text.sumOf { scoreChar(s, it) }
            if (score > bestScore) {
                best = s; bestScore = score
            }
        }
        return best
    }

    private fun scoreChar(s: Script, ch: Char): Int {
        val c = ch.code
        val han = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF
        val kana = c in 0x3040..0x30FF
        val hangul = c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F
        val deva = c in 0x0900..0x097F
        return when (s) {
            Script.CHINESE -> if (han) 2 else 0
            Script.JAPANESE -> if (kana) 3 else if (han) 1 else 0
            Script.KOREAN -> if (hangul) 2 else 0
            Script.DEVANAGARI -> if (deva) 2 else 0
            Script.LATIN -> 0
        }
    }

    private fun hasScript(text: String, s: Script): Boolean = text.any { scoreChar(s, it) > 0 }

    /**
     * Dòng có chữ của bộ chữ chính lấy từ bộ nhận dạng chuyên; dòng chữ La-tinh lấy từ bộ La-tinh,
     * trừ chỗ chồng lên dòng của bộ chữ chính (bộ La-tinh đọc chữ Hán thành rác).
     */
    private fun mergeScripts(primary: List<TextLine>, s: Script, latin: List<TextLine>): List<TextLine> {
        val scriptLines = primary.filter { hasScript(it.text, s) }
        val latinKept = latin.filter { l -> scriptLines.none { overlap(it, l) >= 0.3f } }
        val extra = primary.filter { p ->
            !hasScript(p.text, s) && latinKept.none { overlap(it, p) >= 0.3f }
        }
        return scriptLines + latinKept + extra
    }

    /** Chọn giữa cách đọc của ML Kit và Tesseract theo mức "giống ngôn ngữ thật". */
    private suspend fun arbitrate(ml: List<TextLine>, tessLines: List<TextLine>): List<TextLine> {
        val result = ml.toMutableList()
        val accepted = ArrayList<TextLine>()
        for (t in tessLines) {
            val tScore = detector.score(t.text)
            val over = result.filter { overlap(it, t) >= 0.3f }
            if (over.isEmpty()) {
                if (tScore >= 0.8f) accepted.add(t)
                continue
            }
            val mlBad = over.any { OcrQuality.isMisreadLine(it.text) }
            val mScore = if (mlBad) 0f else detector.score(over.sortedBy { it.left }.joinToString(" ") { it.text })
            if (tScore >= 0.5f && tScore > mScore + 0.1f) {
                result.removeAll(over.toSet())
                accepted.add(t)
            }
        }
        return result + accepted
    }

    private fun scriptMatches(text: String, tessLang: String): Boolean =
        if (tessLang in CYRILLIC) OcrQuality.cyrillicRatio(text) >= 0.6f
        else OcrQuality.nonLatinRatio(text) >= 0.6f

    private val CYRILLIC = setOf("rus", "ukr", "bel", "bul", "srp", "mkd", "kaz", "kir", "mon", "tgk", "tat")

    /** Vùng bao quanh các dòng chữ rác, nới rộng để lấy cả tiêu đề/dòng lân cận. */
    private fun regionAround(lines: List<TextLine>, w: Int, h: Int): Rect? {
        if (lines.isEmpty()) return null
        val lh = lines.map { it.height }.sorted()[lines.size / 2]
        val r = Rect(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })
        r.inset(-2 * lh, -4 * lh)
        return Rect(max(0, r.left), max(0, r.top), min(w, r.right), min(h, r.bottom))
    }

    /** Diện tích giao / diện tích của khung nhỏ hơn. */
    private fun overlap(a: TextLine, b: TextLine): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val minArea = min(a.width.toLong() * a.height, b.width.toLong() * b.height).coerceAtLeast(1)
        return (iw.toLong() * ih).toFloat() / minArea
    }

    fun close() {
        synchronized(recognizers) {
            recognizers.values.forEach { runCatching { it.close() } }
            recognizers.clear()
        }
        tess.close()
    }
}
