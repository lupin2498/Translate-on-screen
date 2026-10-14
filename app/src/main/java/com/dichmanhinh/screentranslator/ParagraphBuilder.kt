package com.dichmanhinh.screentranslator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Một dòng chữ OCR (tọa độ pixel trên ảnh chụp). */
data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Một đoạn văn: các dòng liền nhau được ghép lại để dịch trọn câu. */
data class Paragraph(
    val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val lines: Int
)

/**
 * Ghép các dòng OCR thành đoạn văn trước khi dịch.
 *
 * Nếu dịch từng khối/dòng riêng lẻ, câu bị cắt giữa chừng nên Google Dịch mất ngữ cảnh
 * (ví dụ "Ses morceaux" -> "Quân cờ của anh", "drill" -> "máy khoan", "réseaux / sociaux"
 * -> "mạng / mạng xã hội"). Ghép lại thành đoạn hoàn chỉnh thì bản dịch mạch lạc hơn.
 *
 * Quy tắc ghép dòng B vào đoạn có dòng cuối A (A nằm ngay trên B):
 * - cỡ chữ gần bằng nhau (chiều cao khung dòng lệch không quá 50%);
 * - khoảng cách dọc nhỏ (≤ 0.8 chiều cao dòng) – khoảng cách lớn là đoạn khác/phần tử khác;
 * - cùng lề trái, hoặc chồng nhau theo chiều ngang (chữ căn giữa);
 * - không phải chỗ ngắt câu thật: A kết thúc bằng dấu câu và B bắt đầu bằng chữ hoa/chữ số,
 *   hoặc A ngắn hơn hẳn bề rộng cột mà B bắt đầu bằng một từ viết hoa chữ đầu (tiêu đề, dòng
 *   xuống hàng chủ động). Từ hoa sai kiểu OCR như "SOciaux" không tính là đầu câu.
 */
object ParagraphBuilder {
    // Khung OCR của dòng không có nét thấp (g, p, q, y) thấp hơn ~25-30% dòng có nét thấp,
    // nên so sánh với dòng cao hơn và cho phép chênh lệch tới 50%.
    private const val HEIGHT_RATIO = 1.5f
    private const val MAX_GAP = 0.8f
    private const val MIN_GAP = -0.5f
    private const val SHORT_LINE = 0.8f
    private const val TERMINAL = ".!?…:;。！？"
    private const val TRAILING = "\"'”’»)]—–- "
    private const val LEADING = "\"'“‘«([ "

    private class Para(first: TextLine) {
        val lines = arrayListOf(first)
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        val last get() = lines.last()

        fun add(l: TextLine) {
            lines.add(l)
            left = min(left, l.left); top = min(top, l.top)
            right = max(right, l.right); bottom = max(bottom, l.bottom)
        }
    }

    fun build(input: List<TextLine>): List<Paragraph> {
        val all = input.filter { it.text.isNotBlank() && it.height > 0 && it.width > 0 }
        // Phiên âm nhỏ phía trên chữ Hán (pinyin/furigana): không dịch
        val lines = all.filterNot { isRuby(it, all) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        // Ô của bảng/danh sách: mỗi hàng là một mục riêng, không ghép theo chiều dọc
        val cells = lines.filter { isCell(it, lines) }.toHashSet()
        val paras = ArrayList<Para>()
        for (line in lines) {
            var best: Para? = null
            var bestGap = Int.MAX_VALUE
            for (p in paras) {
                val prev = p.last
                val hMin = min(line.height, prev.height).toFloat()
                val hMax = max(line.height, prev.height).toFloat()
                if (hMax / hMin > HEIGHT_RATIO) continue
                val gap = line.top - prev.bottom
                if (gap < MIN_GAP * hMin || gap > MAX_GAP * hMax) continue
                val overlap = min(line.right, p.right) - max(line.left, p.left)
                val aligned = abs(line.left - p.left) <= hMin ||
                    overlap >= 0.6f * min(line.width, p.right - p.left)
                if (!aligned) continue
                if (isHardBreak(prev, line, lines)) continue
                if (prev in cells || (line in cells && !isContinuation(prev, line))) continue
                if (gap < bestGap) {
                    best = p; bestGap = gap
                }
            }
            if (best == null) paras.add(Para(line)) else best.add(line)
        }
        return paras.map { p ->
            val text = fixOcrCase(joinLines(p.lines.map { it.text }))
            Paragraph(text, p.left, p.top, p.right, p.bottom, p.lines.size)
        }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * Ô bảng/danh sách: dòng ngắn có "hàng xóm" cùng hàng (chữ khác nằm ngang hàng, cách một
     * khoảng) – ví dụ bảng từ vựng "好的 | hảo tơ | Vâng!", thanh nút "Lưu | Chia sẻ".
     */
    private fun isCell(t: TextLine, all: List<TextLine>): Boolean {
        if (!isShortItem(t.text)) return false
        return all.any { o ->
            if (o === t) return@any false
            val hMin = min(t.height, o.height)
            val hMax = max(t.height, o.height)
            val vOverlap = min(t.bottom, o.bottom) - max(t.top, o.top)
            val hGap = max(o.left - t.right, t.left - o.right)
            val centerDy = abs((t.top + t.bottom) - (o.top + o.bottom)) / 2f
            vOverlap >= 0.5f * hMin && hGap >= 0.6f * hMin && centerDy <= 0.4f * hMax
        }
    }

    private fun isShortItem(text: String): Boolean {
        val t = text.trim()
        val cjk = t.count { isCjk(it) }
        if (cjk > 0) return cjk <= 6
        val words = t.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
        return words <= 3 && t.length <= 20
    }

    /** Dòng sau rõ ràng là phần tiếp của câu ở dòng trước (chữ La-tinh, chưa hết câu, chữ thường). */
    private fun isContinuation(prev: TextLine, next: TextLine): Boolean {
        if (endsSentence(prev.text)) return false
        val c = next.text.trimStart { it in LEADING }.firstOrNull() ?: return false
        return c.isLetter() && c.isLowerCase()
    }

    /**
     * Phiên âm (ruby): dòng chữ thường La-tinh hoặc kana cỡ nhỏ nằm sát phía trên một dòng có chữ
     * Hán và nằm gọn trong bề ngang của dòng đó – ví dụ pinyin "hǎo de" trên "好的".
     */
    private fun isRuby(t: TextLine, all: List<TextLine>): Boolean {
        val letters = t.text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val kanaOnly = letters.all { it.code in 0x3040..0x30FF }
        val pinyinLike = letters.none { it.isUpperCase() } &&
            letters.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        if (!kanaOnly && !pinyinLike) return false
        return all.any { b ->
            b !== t && b.text.any { isHan(it) } &&
                t.height <= 0.75f * b.height &&
                b.top - t.bottom in (-(0.3f * t.height).toInt())..((0.6f * b.height).toInt()) &&
                t.left >= b.left - b.height && t.right <= b.right + b.height
        }
    }

    private fun isHan(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF
    }

    /** Có phải chỗ bắt đầu câu/ý mới (không ghép) không. */
    private fun isHardBreak(prev: TextLine, next: TextLine, all: List<TextLine>): Boolean {
        val c = next.text.trimStart { it in LEADING }.firstOrNull() ?: return false
        if (c in "•·▪●◦■□►▶✓✔☐☑") return true // gạch đầu dòng
        if ((c.isUpperCase() || c.isDigit()) && endsSentence(prev.text)) return true
        // Dòng ngắn + từ viết hoa = xuống dòng chủ động. Chỉ đúng với chữ căn trái: chữ căn giữa
        // có độ dài dòng thay đổi theo thiết kế ("...звонок. Жанна / Болотова" là một họ tên).
        if (isCentered(prev, next)) return false
        if (startsTitleWord(next.text) && prev.width < SHORT_LINE * columnWidth(prev, all)) return true
        return false
    }

    /** Hai dòng căn giữa: tâm thẳng hàng nhưng lề trái lệch nhau. */
    private fun isCentered(a: TextLine, b: TextLine): Boolean {
        val h = max(a.height, b.height)
        val centerDiff = abs((a.left + a.right) - (b.left + b.right)) / 2
        return centerDiff <= h && abs(a.left - b.left) > h
    }

    /** Từ đầu tiên viết hoa chữ cái đầu và chữ thường phía sau ("Situation", "En", "A"). */
    private fun startsTitleWord(text: String): Boolean {
        val word = text.trimStart { it in LEADING }.takeWhile { it.isLetter() }
        if (word.isEmpty() || !word[0].isUpperCase()) return false
        return word.length == 1 || word[1].isLowerCase()
    }

    /**
     * Sửa lỗi hoa/thường kiểu OCR: "SOciaux" -> "sociaux" (giữa câu) / "Sociaux" (đầu câu).
     * Chỉ áp dụng cho từ có đúng 2 chữ hoa đầu rồi toàn chữ thường.
     */
    fun fixOcrCase(text: String): String {
        val re = Regex("(?<!\\p{L})(\\p{Lu})(\\p{Lu})(\\p{Ll}{2,})(?!\\p{L})")
        return re.replace(text) { m ->
            val before = text.substring(0, m.range.first).trimEnd { it in TRAILING }
            val sentenceStart = before.isEmpty() || before.last() in TERMINAL
            val first = if (sentenceStart) m.groupValues[1] else m.groupValues[1].lowercase()
            first + m.groupValues[2].lowercase() + m.groupValues[3]
        }
    }

    /** Bề rộng lớn nhất của các dòng cùng cột (cùng lề trái, cỡ chữ tương tự). */
    private fun columnWidth(line: TextLine, all: List<TextLine>): Int {
        var w = line.width
        for (o in all) {
            if (abs(o.left - line.left) > line.height) continue
            val r = o.height.toFloat() / line.height
            if (r < 1f / HEIGHT_RATIO || r > HEIGHT_RATIO) continue
            w = max(w, o.width)
        }
        return w
    }

    fun endsSentence(text: String): Boolean {
        val t = text.trimEnd { it in TRAILING }
        return t.isNotEmpty() && t.last() in TERMINAL
    }

    /** Nối các dòng: bỏ gạch nối cuối dòng, không chèn dấu cách giữa chữ CJK. */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(t); continue
            }
            val last = sb[sb.length - 1]
            val hyphen = last == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && t[0].isLetter()
            when {
                // "transla-" + "tion" -> "translation"; "Jean-" + "Pierre" -> "Jean-Pierre"
                hyphen && t[0].isLowerCase() -> sb.setLength(sb.length - 1)
                hyphen -> Unit
                isCjk(last) || isCjk(t[0]) -> Unit
                else -> sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }

    /** Có nội dung đáng dịch: ít nhất 2 chữ cái liền nhau hoặc một chữ CJK. */
    fun isTranslatable(text: String): Boolean {
        var run = 0
        for (ch in text) {
            if (isCjk(ch)) return true
            run = if (ch.isLetter()) run + 1 else 0
            if (run >= 2) return true
        }
        return false
    }

    fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xFF00..0xFFEF || c in 0x3000..0x303F || c in 0xAC00..0xD7AF
    }
}
