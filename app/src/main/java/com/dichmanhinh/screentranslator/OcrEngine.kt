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

        // Dòng chữ của bộ chữ chính + dòng La-tinh; bỏ phiên âm pinyin/furigana nằm trên chữ Hán
        val primary = forced ?: bestScript(results)
        val latinRaw = linesOf(results[Script.LATIN])
        val primaryLines = if (primary != null) primaryLinesOf(results[primary], primary, bitmap, latinRaw)
        else emptyList()
        val hanBoxes = primaryLines.filter { ParagraphBuilder.hasHan(it.text) }
        val latin = ParagraphBuilder.removeRuby(latinRaw, hanBoxes)
        var lines = if (primary != null) {
            mergeScripts(ParagraphBuilder.removeRuby(primaryLines, hanBoxes), primary, latin)
        } else latin

        // Chữ Kirin bị ML Kit đọc thành chữ La-tinh rác -> đọc lại bằng Tesseract
        val misread = lines.filter { OcrQuality.isMisreadLine(it.text) }
        val auto = sourceLang == Languages.AUTO
        // Chỉ nghi chữ Kirin khi có đủ bằng chứng (≥ 3 từ đáng ngờ), tránh tải dữ liệu vô ích
        val suspicious = misread.sumOf { OcrQuality.misreadTokenCount(it.text) } >= 3
        val useTessLang = tessLang ?: if (auto && suspicious) "rus" else null
        var tessAccepted: List<TessLine> = emptyList()
        if (useTessLang != null) {
            val region = if (tessLang != null) null else regionAround(misread, bitmap.width, bitmap.height)
            val tLines = tess.recognize(bitmap, useTessLang, region, onDownload)
                .filter { it.confidence >= 50f && scriptMatches(it.line.text, useTessLang) }
            val (kept, accepted) = arbitrate(lines, tLines)
            android.util.Log.i(
                "ScreenTranslator",
                "tesseract[$useTessLang] lines=${tLines.size} accepted=${accepted.size} ml=${lines.size}->${kept.size}"
            )
            lines = kept
            tessAccepted = accepted
        }
        // Không bao giờ dịch chữ rác còn sót lại (một từ lạ đơn lẻ như tên hàm thì giữ)
        fun isGarbage(l: TextLine) = OcrQuality.isMisreadLine(l.text) &&
            (useTessLang != null || OcrQuality.misreadTokenCount(l.text) >= 2)
        val garbage = lines.count { isGarbage(it) }
        lines = lines.filterNot { isGarbage(it) }

        lastReport = Report(
            primary = (primary ?: Script.LATIN).name,
            misreadLines = misread.size,
            tesseractLang = useTessLang,
            tesseractLines = tessAccepted.size,
            droppedGarbage = garbage,
        )
        return (ParagraphBuilder.build(lines) + tessParagraphs(tessAccepted))
            .filter { ParagraphBuilder.isTranslatable(it.text) }
            .map { OcrBlock(it.text, Rect(it.left, it.top, it.right, it.bottom), it.lines) }
    }

    /** Các dòng của kết quả ML Kit. */
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

    /**
     * Dòng của bộ chữ chính (Trung/Nhật/Hàn/Devanagari). Bộ chữ Trung có lúc gộp nhiều hàng của một
     * bảng thành một "dòng" cao bất thường: cắt vùng đó theo từng hàng (vị trí hàng lấy từ các dòng
     * chữ La-tinh) và nhận dạng lại từng dải. Dòng trộn chữ Hán với phiên âm thì tách theo bộ chữ.
     */
    private suspend fun primaryLinesOf(
        text: Text?, s: Script, bitmap: Bitmap, latin: List<TextLine>
    ): List<TextLine> {
        val raw = linesOf(text)
        if (raw.isEmpty()) return raw
        val med = raw.map { it.height }.sorted()[raw.size / 2]
        val out = ArrayList<TextLine>()
        for (l in raw) {
            if (l.height > 1.7f * med) {
                val bands = rowBands(l, latin)
                if (bands.size >= 2) {
                    android.util.Log.i("ScreenTranslator", "re-ocr multi-row line '${l.text.take(40)}' bands=${bands.size}")
                    for (b in bands) out.addAll(reocr(bitmap, s, b))
                    continue
                }
            }
            out.addAll(ParagraphBuilder.splitByScript(l))
        }
        return out
    }

    /** Chia khung [l] thành các dải ngang theo vị trí các hàng chữ La-tinh nằm trong nó. */
    private fun rowBands(l: TextLine, latin: List<TextLine>): List<Rect> {
        val inside = latin.filter { (it.top + it.bottom) / 2 in l.top..l.bottom }
        if (inside.size < 2) return emptyList()
        val tol = inside.map { it.height }.sorted()[inside.size / 2] / 2
        val centers = inside.map { (it.top + it.bottom) / 2 }.sorted()
        val rows = ArrayList<Int>()
        var cur = arrayListOf(centers[0])
        for (c in centers.drop(1)) {
            if (c - cur.last() > tol) {
                rows.add(cur.average().toInt()); cur = arrayListOf(c)
            } else cur.add(c)
        }
        rows.add(cur.average().toInt())
        if (rows.size < 2) return emptyList()
        val bounds = listOf(l.top) + rows.zipWithNext { a, b -> (a + b) / 2 } + listOf(l.bottom)
        return bounds.zipWithNext { a, b -> Rect(l.left, a, l.right, b) }
    }

    /** Nhận dạng lại một vùng nhỏ của ảnh bằng bộ chữ [s]. */
    private suspend fun reocr(bitmap: Bitmap, s: Script, r: Rect): List<TextLine> {
        val left = max(0, r.left - 4)
        val top = max(0, r.top - 4)
        val right = min(bitmap.width, r.right + 4)
        val bottom = min(bitmap.height, r.bottom + 4)
        if (right - left < 8 || bottom - top < 8) return emptyList()
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            val res = recognizer(s).process(InputImage.fromBitmap(crop, 0)).await()
            linesOf(res).map { TextLine(it.text, it.left + left, it.top + top, it.right + left, it.bottom + top) }
                .flatMap { ParagraphBuilder.splitByScript(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            crop.recycle()
        }
    }

    /**
     * Ghép dòng Tesseract thành đoạn theo khối văn bản Tesseract đã nhận ra (khung chữ, tiêu đề...),
     * chỉ tách khi có khoảng trống dọc lớn. Khung dòng của Tesseract có thể lẫn biểu tượng xung
     * quanh nên không dùng luật so cỡ chữ như với ML Kit.
     */
    private fun tessParagraphs(lines: List<TessLine>): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        for ((_, group) in lines.groupBy { it.block }) {
            val sorted = group.map { it.line }.sortedBy { it.top }
            val med = sorted.map { it.height }.sorted()[sorted.size / 2]
            var run = arrayListOf(sorted[0])
            for (i in 1 until sorted.size) {
                if (sorted[i].top - run.last().bottom > 1.2f * med) {
                    out.add(toParagraph(run)); run = arrayListOf(sorted[i])
                } else run.add(sorted[i])
            }
            out.add(toParagraph(run))
        }
        return out
    }

    private fun toParagraph(run: List<TextLine>) = Paragraph(
        ParagraphBuilder.fixOcrCase(ParagraphBuilder.joinLines(run.map { it.text })),
        run.minOf { it.left }, run.minOf { it.top }, run.maxOf { it.right }, run.maxOf { it.bottom }, run.size
    )

    /**
     * Bộ chữ không phải La-tinh xuất hiện nhiều nhất (null nếu gần như không có).
     */
    private fun bestScript(results: Map<Script, Text?>): Script? {
        var best: Script? = null
        var bestScore = 0
        for ((s, text) in results) {
            if (s == Script.LATIN || text == null) continue
            val chars = text.text.count { scoreChar(s, it) > 0 }
            val letters = text.text.count { it.isLetter() }.coerceAtLeast(1)
            // Bộ chữ phải chiếm phần đáng kể (≥ 10% chữ cái, hoặc ≥ 20 ký tự): bộ nhận dạng chữ
            // Nhật/Trung đọc chữ Nga cũng ra vài ký tự kana/Hán lạc.
            if (chars < 2 || (chars < 20 && chars < 0.10f * letters)) continue
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

    /**
     * Chọn giữa cách đọc của ML Kit và Tesseract theo mức "giống ngôn ngữ thật".
     * Không bao giờ thay dòng có chữ Hán/kana/hangul/Devanagari (ML Kit chuyên các chữ này).
     */
    private suspend fun arbitrate(
        ml: List<TextLine>, tessLines: List<TessLine>
    ): Pair<List<TextLine>, List<TessLine>> {
        val result = ml.toMutableList()
        val accepted = ArrayList<TessLine>()
        for (t in tessLines) {
            val text = t.line.text
            if (text.count { it.isLetter() } < 4) continue
            val over = result.filter { overlap(it, t.line) >= 0.3f }
            if (over.any { m -> m.text.any { ParagraphBuilder.isCjk(it) || it.code in 0x0900..0x097F } }) continue
            val tScore = detector.score(text)
            if (over.isEmpty()) {
                if (tScore >= 0.8f && text.count { it.isLetter() } >= 6) accepted.add(t)
                continue
            }
            val mlBad = over.any { OcrQuality.isMisreadLine(it.text) }
            val mScore = if (mlBad) 0f else detector.score(over.sortedBy { it.left }.joinToString(" ") { it.text })
            if ((mlBad && tScore >= 0.6f) || (tScore >= 0.8f && tScore > mScore + 0.3f)) {
                result.removeAll(over.toSet())
                accepted.add(t)
            }
        }
        return result to accepted
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
