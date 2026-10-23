package com.dichmanhinh.screentranslator.ai

import java.util.Locale

/** Nhận biết nhanh chữ Hán (khi không rõ ngôn ngữ nguồn). */
internal object LangHint {
    fun looksChinese(s: String): Boolean {
        var han = 0
        var letters = 0
        for (ch in s) {
            if (!ch.isLetter()) continue
            letters++
            val c = ch.code
            if (c in 0x3040..0x30FF || c in 0xAC00..0xD7AF) return false // có kana/hangul: Nhật/Hàn
            if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF) han++
        }
        return letters > 0 && han * 2 >= letters
    }
}

/** Tên ngôn ngữ dùng trong prompt (tiếng Anh cho mô hình đa năng, tiếng Trung cho HY-MT). */
object AiLang {
    /** Tên tiếng Trung theo đúng bảng ngôn ngữ trong tài liệu của Hunyuan-MT. */
    private val HY_MT_ZH = mapOf(
        "zh" to "中文", "zh-CN" to "中文", "zh-TW" to "繁体中文", "en" to "英语", "fr" to "法语",
        "pt" to "葡萄牙语", "es" to "西班牙语", "ja" to "日语", "tr" to "土耳其语", "ru" to "俄语",
        "ar" to "阿拉伯语", "ko" to "韩语", "th" to "泰语", "it" to "意大利语", "de" to "德语",
        "vi" to "越南语", "ms" to "马来语", "id" to "印尼语", "tl" to "菲律宾语", "hi" to "印地语",
        "pl" to "波兰语", "cs" to "捷克语", "nl" to "荷兰语", "km" to "高棉语", "my" to "缅甸语",
        "fa" to "波斯语", "gu" to "古吉拉特语", "ur" to "乌尔都语", "te" to "泰卢固语", "mr" to "马拉地语",
        "iw" to "希伯来语", "he" to "希伯来语", "bn" to "孟加拉语", "ta" to "泰米尔语", "uk" to "乌克兰语",
        "bo" to "藏语", "kk" to "哈萨克语", "mn" to "蒙古语", "ug" to "维吾尔语", "yue" to "粤语",
    )

    /** Ngôn ngữ đích mà HY-MT 1.5 được huấn luyện. */
    fun hyMtSupports(code: String): Boolean = code in HY_MT_ZH || base(code) in HY_MT_ZH

    fun base(code: String): String = when (val b = code.substringBefore('-').lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "jw" -> "jv"
        else -> b
    }

    private fun locale(code: String): Locale = when (code) {
        "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        else -> Locale.forLanguageTag(base(code))
    }

    fun english(code: String): String = when (code) {
        "zh-CN", "zh" -> "Simplified Chinese"
        "zh-TW" -> "Traditional Chinese"
        "tl" -> "Filipino"
        "mni-Mtei" -> "Meitei (Manipuri)"
        "ckb" -> "Central Kurdish (Sorani)"
        else -> locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
    }

    fun chinese(code: String): String =
        HY_MT_ZH[code] ?: HY_MT_ZH[base(code)] ?: locale(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
            .takeIf { it.isNotBlank() && it != code } ?: english(code)

    fun isChinese(code: String): Boolean = base(code) == "zh" || code == "yue"
}

/**
 * Dựng prompt và đọc kết quả của mô hình. Mỗi đoạn chữ trên màn hình được bọc trong thẻ
 * `<sN>…</sN>` để mô hình dịch cả màn hình trong một lần (giữ ngữ cảnh, xưng hô thống nhất) mà
 * vẫn tách được bản dịch về đúng vị trí của từng đoạn.
 */
object AiPrompts {

    // ----------------------------------------------------------------- Hunyuan-MT

    /**
     * Mẫu prompt chính thức của Hunyuan-MT cho một đoạn. Thử nghiệm với mô hình thật: dịch từng đoạn
     * riêng chính xác hơn hẳn gộp nhiều đoạn (gộp thì nội dung các đoạn lẫn vào nhau, "明天" bị dịch
     * thành "hôm nay"), và mẫu "kèm ngữ cảnh" đôi khi dịch nhầm sang câu ngữ cảnh.
     */
    fun hyMtSingle(text: String, source: String, target: String): String {
        val t = oneLine(text)
        return if (AiLang.isChinese(source) || AiLang.isChinese(target) || LangHint.looksChinese(t)) {
            "将以下文本翻译为${AiLang.chinese(target)}，注意只需要输出翻译后的结果，不要额外解释：\n\n$t"
        } else {
            "Translate the following segment into ${AiLang.english(target)}, without additional explanation.\n\n$t"
        }
    }

    // ----------------------------------------------------------------- mô hình đa năng

    /** Gợi ý riêng cho từng ngôn ngữ đích để bản dịch tự nhiên hơn. */
    private fun targetHints(target: String): String = when (AiLang.base(target)) {
        "vi" -> "- Write natural, idiomatic Vietnamese as a native speaker would, not word-by-word.\n" +
            "- Choose Vietnamese pronouns (tôi, bạn, anh, em, chị, cậu, tớ, ông, bà, ngài…) that fit the " +
            "speakers' relationship and tone; keep them consistent across the screen. Consecutive subtitle or " +
            "chat lines are often a dialogue: work out who says each line to whom (a reply comes from the " +
            "other person) before choosing pronouns.\n" +
            "- Chinese personal names, places, titles and martial-arts terms: use Sino-Vietnamese (Hán Việt) " +
            "readings (e.g. 李明 -> Lý Minh, 北京 -> Bắc Kinh). Russian/Japanese/Korean names: keep the usual " +
            "romanization.\n" +
            "- UI labels (buttons, menus) should be short, like a Vietnamese app would say them.\n"
        "zh" -> "- Write natural, fluent ${AiLang.english(target)}.\n"
        else -> "- Write natural, fluent ${AiLang.english(target)} as a native speaker would.\n"
    }

    fun generalSystem(target: String, withImage: Boolean): String {
        val lang = AiLang.english(target)
        return "You are the translation engine of an on-screen translator app on a phone. " +
            "The user sends text that OCR recognized from a screenshot, split into numbered segments " +
            "<s1>…</s1>, <s2>…</s2>… in screen order" +
            (if (withImage) ", together with the screenshot itself" else "") + ".\n" +
            "Rules:\n" +
            "- OCR makes mistakes: wrong or missing diacritics, confused letters (rn/m, l/I/1, 0/O, " +
            "Latin/Cyrillic look-alikes), wrong similar-looking Chinese characters, broken or merged words. " +
            (if (withImage) "Look at the image to read what is really written. "
            else "Use the context of the whole screen to infer the intended text. ") +
            "Translate what is really meant, never the OCR noise.\n" +
            "- Translate every segment into $lang. Use the whole screen as context so terms, names and " +
            "pronouns stay consistent.\n" +
            targetHints(target) +
            "- Keep numbers, prices, dates, URLs, @handles, code and brand names as they are. A segment " +
            "already in $lang, or that is only a name/number, is copied unchanged.\n" +
            "- Output ONLY the translations, each wrapped in the same tag as its segment " +
            "(<s1>…</s1>, <s2>…</s2>…), one per line, same count and order. No notes, no explanations."
    }

    fun generalUser(
        segments: List<String>, sources: List<String>, target: String, imageMarker: String?, context: String?,
    ): String {
        val sb = StringBuilder()
        if (imageMarker != null) sb.append(imageMarker).append("\n")
        val known = sources.filter { it != "auto" }.distinct()
        if (known.size == 1) sb.append("Source language (detected, may be wrong): ")
            .append(AiLang.english(known[0])).append("\n")
        if (!context.isNullOrBlank()) {
            sb.append("Other text on the same screen (context only, do not translate):\n")
                .append(context).append("\n")
        }
        sb.append("Translate into ").append(AiLang.english(target)).append(":\n")
        segments.forEachIndexed { i, s -> sb.append("<s${i + 1}>").append(oneLine(s)).append("</s${i + 1}>\n") }
        return sb.toString().trimEnd()
    }

    /** Khi OCR không đọc được chữ nào: để mô hình tự đọc ảnh rồi dịch. */
    fun readImageSystem(target: String): String {
        val lang = AiLang.english(target)
        return "You are the translation engine of an on-screen translator app. You receive a screenshot. " +
            "Read ALL text in the image in natural reading order (including stylized text, speech bubbles " +
            "and signs), group it into lines or paragraphs, and translate each one into $lang.\n" +
            targetHints(target) +
            "Output only, one item per line: <p><o>original text</o><t>translation</t></p>\n" +
            "Skip text that is already in $lang. If there is no text, output <none/>."
    }

    fun readImageUser(imageMarker: String, target: String): String =
        "$imageMarker\nRead and translate the text in this screenshot into ${AiLang.english(target)}."

    // ----------------------------------------------------------------- đọc kết quả

    private val THINK = listOf(
        Regex("<think>[\\s\\S]*?</think>"),
        Regex("<\\|channel>[\\s\\S]*?<channel\\|>"),
        Regex("<\\|?thought\\|?>[\\s\\S]*?<\\|?/?thought\\|?>"),
    )

    /** Bỏ phần "suy nghĩ", khối mã ``` và thẻ <target> mà mô hình có thể thêm vào. */
    fun cleanOutput(raw: String): String {
        var s = raw
        for (r in THINK) s = s.replace(r, "")
        s = s.replace(Regex("^\\s*```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```\\s*$"), "")
        s = s.replace("<target>", "").replace("</target>", "")
        return s.trim()
    }

    private val TAG = Regex("<s(\\d{1,3})>([\\s\\S]*?)(?=</s\\1>|<s\\d{1,3}>|$)")
    private val STRAY_TAGS = Regex("</?(?:s\\d{1,3}|source|target|sn)>")

    /** Tách bản dịch theo thẻ <sN>. Phần tử null = mô hình bỏ sót đoạn đó. */
    fun parseTagged(output: String, count: Int): Array<String?> {
        val res = arrayOfNulls<String>(count)
        val text = cleanOutput(output)
        for (m in TAG.findAll(text)) {
            val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: continue
            if (idx !in 0 until count || res[idx] != null) continue
            val v = tidy(m.groupValues[2])
            if (v.isNotEmpty()) res[idx] = v
        }
        // Chỉ một đoạn mà mô hình trả lời không kèm thẻ
        if (count == 1 && res[0] == null && !text.contains('<')) {
            tidy(text).takeIf { it.isNotEmpty() }?.let { res[0] = it }
        }
        return res
    }

    /** Kết quả dịch một đoạn (không có thẻ). */
    fun parseSingle(output: String): String? {
        val t = cleanOutput(output)
        val tagged = Regex("<s1>([\\s\\S]*?)(?:</s1>|$)").find(t)?.groupValues?.get(1)
        return tidy(tagged ?: t).takeIf { it.isNotEmpty() }
    }

    /** Cặp (chữ gốc, bản dịch) khi mô hình tự đọc ảnh. */
    fun parseReadImage(output: String): List<Pair<String, String>> {
        val text = cleanOutput(output)
        val out = ArrayList<Pair<String, String>>()
        val re = Regex("<o>([\\s\\S]*?)</o>\\s*<t>([\\s\\S]*?)(?:</t>|(?=<p>)|$)")
        for (m in re.findAll(text)) {
            val o = tidy(m.groupValues[1])
            val t = tidy(m.groupValues[2].replace("</p>", ""))
            if (t.isNotEmpty()) out.add(o to t)
        }
        return out
    }

    private fun tidy(s: String): String =
        s.replace(STRAY_TAGS, " ").replace(Regex("[ \\t]+"), " ").lines().joinToString(" ") { it.trim() }.trim()

    /** Mỗi đoạn trên một dòng (ghép đoạn văn bị ngắt dòng khi OCR). */
    fun oneLine(s: String): String = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    // ----------------------------------------------------------------- ước lượng độ dài

    /** Ước lượng số token: chữ Hán/kana/hangul ~1 token/ký tự, chữ khác ~3.5 ký tự/token. */
    fun estimateTokens(s: String): Int {
        var cjk = 0
        var other = 0
        for (ch in s) {
            val c = ch.code
            if (c in 0x3040..0x30FF || c in 0x3400..0x9FFF || c in 0xAC00..0xD7AF) cjk++ else other++
        }
        return cjk + (other + 2) / 3 + 1
    }

    /** Số token tối đa cho bản dịch của các đoạn (bản dịch tiếng Việt thường dài hơn gốc chữ Hán). */
    fun maxOutputTokens(segments: List<String>): Int =
        (segments.sumOf { (estimateTokens(it) * 2.5f).toInt() + 12 } + 48).coerceAtMost(3072)

    /** Chia các đoạn (theo thứ tự) thành lô có tổng token ước lượng ≤ [budget]. */
    fun packBatches(segments: List<String>, budget: Int, maxItems: Int = 40): List<List<Int>> {
        val batches = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var sum = 0
        for (i in segments.indices) {
            val t = estimateTokens(segments[i]) + 6
            if (cur.isNotEmpty() && (sum + t > budget || cur.size >= maxItems)) {
                batches.add(cur); cur = ArrayList(); sum = 0
            }
            cur.add(i); sum += t
        }
        if (cur.isNotEmpty()) batches.add(cur)
        return batches
    }

    /** Bản dịch có vẻ hỏng (lặp vô tận, dài bất thường) -> coi như chưa dịch. */
    fun looksBroken(source: String, translation: String): Boolean {
        val s = estimateTokens(source)
        val t = estimateTokens(translation)
        if (t > 6 * s + 60) return true
        // cùng một cụm lặp lại nhiều lần liên tiếp
        val words = translation.split(' ').filter { it.isNotBlank() }
        if (words.size >= 12) {
            for (n in 1..4) {
                var repeats = 0
                for (i in n until words.size) {
                    if (words[i] == words[i - n]) repeats++ else repeats = 0
                    if (repeats >= 4 * n + 4) return true
                }
            }
        }
        return false
    }
}
