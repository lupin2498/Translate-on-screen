package com.dichmanhinh.screentranslator

/**
 * Đánh giá chất lượng chữ OCR.
 *
 * ML Kit không có mô hình cho chữ Kirin (tiếng Nga, Ukraina...). Bộ nhận dạng La-tinh đọc chữ Kirin
 * thành chữ La-tinh trông giống: "Николай Губенко готовился" -> "HKONan y6eHKO roTOBAncA".
 * Kiểu chữ rác này có dấu hiệu rõ:
 * - chữ số kẹp giữa chữ cái (б -> 6, з -> 3, д -> 9): "y6eHKO", "no3BOHMna", "5onoroBa";
 * - chữ hoa/thường đảo liên tục trong một từ: "roTOBAncA", "MapuaHHe", "CeMbneT", "cnMUKOM".
 */
object OcrQuality {
    private val TOKEN_SPLIT = Regex("[\\s,.;:!?«»\"“”()\\[\\]{}…—–]+")

    /** Một từ có dấu hiệu là chữ Kirin bị đọc thành La-tinh. */
    fun isMisreadToken(token: String): Boolean {
        val t = token.trim { !it.isLetterOrDigit() }
        if (t.length < 3 || t.count { it.isLetter() } < 2) return false
        for (i in 1 until t.length - 1) {
            val a = t[i - 1]
            val b = t[i + 1]
            if (!t[i].isDigit() || !a.isLetter() || !b.isLetter()) continue
            if (b == 's' && i + 1 == t.length - 1) continue // số nhiều: MP3s, A4s
            if (a.isLowerCase() || b.isLowerCase() || t.length >= 5) return true // H2O, B2B thì không
        }
        if (t[0].isDigit() && t[1].isLetter() && t[1].isLowerCase() && t.drop(1).all { it.isLetter() }) return true

        // Các "đoạn" liên tiếp cùng kiểu hoa/thường của phần chữ cái
        val runs = ArrayList<IntArray>() // [1 nếu hoa / 0 nếu thường, độ dài]
        for (c in t) {
            if (!c.isLetter()) continue
            val up = if (c.isUpperCase()) 1 else 0
            if (runs.isNotEmpty() && runs.last()[0] == up) runs.last()[1]++ else runs.add(intArrayOf(up, 1))
        }
        val n = runs.size
        if (n >= 5) return true // đảo hoa/thường liên tục: CeMbneT, cyMraeTe
        if (n >= 2 && runs[0][0] == 1 && runs[0][1] >= 2 && runs[1][1] >= 2) return true // HKONan
        if (n >= 3 && runs[0][0] == 1 && runs[0][1] >= 2) return true // MMHVCTpOM, COnEPHNUE
        for (i in 1 until n - 1) {
            if (runs[i][0] == 1 && runs[i][1] >= 2 && runs[i + 1][0] == 0) return true // roTOBAncA
        }
        // chữ thường rồi kết thúc bằng 3+ chữ hoa: nocneAHMM, cnMUKOM (macOS, iOS thì không)
        if (n >= 2 && runs[n - 2][0] == 0 && runs[n - 2][1] >= 2 && runs[n - 1][0] == 1 && runs[n - 1][1] >= 3) {
            return true
        }
        return false
    }

    private fun tokens(text: String) = text.split(TOKEN_SPLIT).filter { tok -> tok.count { it.isLetter() } >= 2 }

    /** Số từ đáng ngờ trong dòng. */
    fun misreadTokenCount(text: String): Int = tokens(text).count { isMisreadToken(it) }

    /** Dòng có vẻ là chữ Kirin bị đọc thành La-tinh (đủ nhiều từ đáng ngờ). */
    fun isMisreadLine(text: String): Boolean {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return false
        val bad = tokens.count { isMisreadToken(it) }
        val ratio = bad.toFloat() / tokens.size
        return if (tokens.size <= 4) bad >= 1 && ratio >= 0.3f else bad >= 2 && ratio >= 0.25f
    }

    /** Tỉ lệ chữ cái Kirin trong số chữ cái. */
    fun cyrillicRatio(text: String): Float {
        var letters = 0
        var cyr = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (c.code in 0x0400..0x052F) cyr++
        }
        return if (letters == 0) 0f else cyr.toFloat() / letters
    }

    /** Tỉ lệ chữ cái không phải La-tinh (cho các bộ chữ khác: Ả Rập, Thái, Hy Lạp...). */
    fun nonLatinRatio(text: String): Float {
        var letters = 0
        var other = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN) other++
        }
        return if (letters == 0) 0f else other.toFloat() / letters
    }
}
