package com.dichmanhinh.screentranslator

/**
 * Quyết định dịch từng đoạn chữ theo ngôn ngữ của chính đoạn đó.
 *
 * Bài học: nếu gộp nhiều đoạn vào một yêu cầu với sl=auto, Google chỉ nhận diện MỘT ngôn ngữ
 * cho cả lô. Màn hình trộn tiếng Việt (giao diện, phụ đề) với tiếng Trung sẽ bị nhận là tiếng
 * Việt và Google trả nguyên văn mọi đoạn, kể cả chữ Hán. Vì vậy phải nhận diện ngôn ngữ từng
 * đoạn, bỏ qua đoạn đã ở ngôn ngữ đích, rồi gửi theo nhóm cùng ngôn ngữ với sl cụ thể.
 */
object LangRouting {
    const val UNDETERMINED = "und"

    sealed class Action {
        data class Translate(val sl: String) : Action()
        object SkipTarget : Action()
        object SkipRomanized : Action()
    }

    /** Nhận diện chắc chắn theo chữ viết: có kana -> ja, có hangul -> ko, có chữ Hán -> zh. */
    fun scriptLanguage(text: String): String? {
        var han = 0
        var kana = 0
        var hangul = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F -> hangul++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
            }
        }
        return when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            han > 0 -> "zh"
            else -> null
        }
    }

    /** Mã gốc để so sánh ("zh-TW" -> "zh", "he" -> "iw"...). */
    fun base(code: String): String = when (val b = code.substringBefore('-').lowercase()) {
        "he" -> "iw"
        "jv" -> "jw"
        "fil" -> "tl"
        "nb", "nn" -> "no"
        else -> b
    }

    /** Mã ML Kit -> mã Google Dịch; trả về "auto" nếu Google không hỗ trợ. */
    fun toGoogle(tag: String): String {
        val code = when (tag) {
            "zh" -> "zh-CN"
            else -> base(tag)
        }
        return if (code in Languages.codes) code else Languages.AUTO
    }

    fun decide(detected: String, source: String, target: String): Action {
        // Phiên âm La-tinh của tiếng Trung/Nhật (pinyin, romaji) đi kèm chữ gốc: không dịch
        if (detected == "zh-Latn" || detected == "ja-Latn") return Action.SkipRomanized
        val known = detected != UNDETERMINED && !detected.endsWith("-Latn")
        if (known && base(detected) == base(target)) return Action.SkipTarget
        val auto = source == Languages.AUTO
        return when {
            !known -> Action.Translate(if (auto) Languages.AUTO else source)
            !auto && base(detected) == base(source) -> Action.Translate(source)
            else -> Action.Translate(toGoogle(detected))
        }
    }
}
