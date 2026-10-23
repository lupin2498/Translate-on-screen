package com.dichmanhinh.screentranslator

/**
 * Chuẩn bị chữ OCR trước khi gửi Google Dịch. Chữ đọc từ video thường lẫn "rác" làm hỏng câu:
 * - khoảng trắng giữa chữ Hán ("不 是" bị dịch thành "không, nó đấy" thay vì "không phải");
 * - ký tự lạ do đọc nhầm hình ảnh/viền chữ: "ОНА\`", "ПРЯМО).ПЕРЕД", "Жанна © Болотова", "и//)1";
 * - chữ IN HOA toàn bộ (tiêu đề video) khiến Google dịch kém hơn chữ thường (coi như viết tắt, tên riêng).
 */
object TextPrep {
    private val LONE_SYMBOL = Regex("(?<=\\p{L})\\s+[©®™]+\\s+(?=\\p{L})")
    private val JUNK_TAIL = Regex("(?<=\\p{L})[/|\\\\`)\\]}]*[/|\\\\`][/|\\\\`)\\]}]*\\d*(?=\\s|$)")
    private val SPACES = Regex("[ \\t]{2,}")
    private val WORD_SPLIT = Regex("\\s+")

    /** Làm sạch chữ OCR (giữ nguyên nghĩa, chỉ bỏ ký tự rác và khoảng trắng thừa). */
    fun clean(text: String): String {
        var s = joinCjkSpaces(fixMixedScript(text))
        s = s.replace(LONE_SYMBOL, " ")
        s = s.replace(JUNK_TAIL, "")
        s = dropUnbalancedClosers(s)
        return s.replace(SPACES, " ").trim()
    }

    /** Cặp chữ La-tinh / Kirin trông giống hệt nhau mà OCR hay đọc lẫn. */
    private val LOOKALIKES = listOf(
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у', 'k' to 'к',
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О',
        'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
    )
    private val LAT_TO_CYR = LOOKALIKES.toMap()
    private val CYR_TO_LAT = LOOKALIKES.associate { (l, c) -> c to l }

    private fun isCyrillic(c: Char) = c in '\u0400'..'\u04FF'
    private fun isLatin(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    /**
     * Một từ lẫn cả chữ La-tinh và chữ Kirin là do OCR đọc lẫn chữ trông giống nhau ("Пpивeт" có p, e
     * La-tinh): đổi các chữ lẫn về bộ chữ chiếm đa số trong từ, để máy dịch nhận ra đúng từ "Привет".
     * Chỉ đổi khi mọi chữ thiểu số đều có chữ giống hệt ở bộ chữ kia; từ thuần một bộ chữ giữ nguyên.
     */
    fun fixMixedScript(s: String): String {
        if (s.none { isCyrillic(it) } || s.none { isLatin(it) }) return s
        val sb = StringBuilder(s)
        var i = 0
        while (i < sb.length) {
            if (!sb[i].isLetter()) { i++; continue }
            var j = i
            var cyr = 0
            var lat = 0
            while (j < sb.length && sb[j].isLetter()) {
                if (isCyrillic(sb[j])) cyr++ else if (isLatin(sb[j])) lat++
                j++
            }
            if (cyr > 0 && lat > 0) {
                val word = sb.substring(i, j)
                val toCyr = cyr >= lat && word.all { !isLatin(it) || it in LAT_TO_CYR }
                val toLat = !toCyr && lat > cyr && word.all { !isCyrillic(it) || it in CYR_TO_LAT }
                if (toCyr || toLat) {
                    for (k in i until j) {
                        val c = sb[k]
                        sb.setCharAt(k, if (toCyr) LAT_TO_CYR[c] ?: c else CYR_TO_LAT[c] ?: c)
                    }
                }
            }
            i = j
        }
        return sb.toString()
    }

    /** Tiếng Trung/Nhật không dùng khoảng trắng giữa các chữ: khoảng trắng ở đó là do OCR. */
    fun joinCjkSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' && sb.isNotEmpty() && noSpaceScript(sb.last())) {
                var j = i
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && noSpaceScript(s[j])) {
                    i = j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Chữ Hán, kana, dấu câu CJK, ký tự toàn độ rộng (không gồm Hangul: tiếng Hàn có dấu cách). */
    private fun noSpaceScript(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3000..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xF900..0xFAFF || c in 0xFF00..0xFF9F
    }

    /**
     * Bỏ dấu đóng ngoặc không có dấu mở tương ứng đứng ngay sau một từ: "ПРЯМО).ПЕРЕД" -> "ПРЯМО ПЕРЕД".
     * Giữ đánh số kiểu "1)", "a)" và mặt cười ":)".
     */
    fun dropUnbalancedClosers(s: String): String {
        val sb = StringBuilder(s.length)
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' || c == '[' || c == '{' -> {
                    depth++
                    sb.append(c)
                }
                (c == ')' || c == ']' || c == '}') && depth > 0 -> {
                    depth--
                    sb.append(c)
                }
                (c == ')' || c == ']' || c == '}') && lettersBefore(sb) >= 2 -> {
                    val next = s.getOrNull(i + 1)
                    if (next != null && next in ".,;:" && s.getOrNull(i + 2)?.isLetter() == true) {
                        sb.append(' ') // ")." dính liền chữ sau: rác, không phải hết câu
                        i += 2
                        continue
                    }
                    if (next != null && next.isLetter()) sb.append(' ')
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun lettersBefore(sb: StringBuilder): Int {
        var n = 0
        var k = sb.length - 1
        while (k >= 0 && sb[k].isLetter()) {
            n++
            k--
        }
        return n
    }

    /** Đoạn viết IN HOA toàn bộ (ít nhất 2 từ): "ОНА ПОЗВОНИЛА СОПЕРНИЦЕ", "BREAKING NEWS TODAY". */
    fun isAllCaps(text: String): Boolean {
        var upper = 0
        var lower = 0
        for (c in text) {
            if (c.isUpperCase()) upper++ else if (c.isLowerCase()) lower++
        }
        if (upper + lower < 6 || upper < 0.9f * (upper + lower)) return false
        return text.split(WORD_SPLIT).count { w -> w.count { it.isLetter() } >= 2 } >= 2
    }

    /** "ОНА ПОЗВОНИЛА. ПЕРЕД СВАДЬБОЙ" -> "Она позвонила. Перед свадьбой". */
    fun toSentenceCase(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (i in lower.indices) {
            val c = lower[i]
            if (capNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capNext = false
                continue
            }
            sb.append(c)
            if ((c in ".!?…" && lower.getOrNull(i + 1)?.isWhitespace() != false) || c == '\n') capNext = true
        }
        return sb.toString()
    }
}
