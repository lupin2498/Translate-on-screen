package com.dichmanhinh.screentranslator

import java.util.Locale

data class Language(val code: String, val name: String) {
    override fun toString(): String = name
}

/** Danh sách ngôn ngữ được Google Dịch hỗ trợ. */
object Languages {
    const val AUTO = "auto"

    private val RAW = listOf(
        "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic",
        "hy" to "Armenian", "as" to "Assamese", "ay" to "Aymara", "az" to "Azerbaijani",
        "bm" to "Bambara", "eu" to "Basque", "be" to "Belarusian", "bn" to "Bengali",
        "bho" to "Bhojpuri", "bs" to "Bosnian", "bg" to "Bulgarian", "ca" to "Catalan",
        "ceb" to "Cebuano", "ny" to "Chichewa", "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)", "co" to "Corsican", "hr" to "Croatian",
        "cs" to "Czech", "da" to "Danish", "dv" to "Dhivehi", "doi" to "Dogri",
        "nl" to "Dutch", "en" to "English", "eo" to "Esperanto", "et" to "Estonian",
        "ee" to "Ewe", "tl" to "Filipino", "fi" to "Finnish", "fr" to "French",
        "fy" to "Frisian", "gl" to "Galician", "ka" to "Georgian", "de" to "German",
        "el" to "Greek", "gn" to "Guarani", "gu" to "Gujarati", "ht" to "Haitian Creole",
        "ha" to "Hausa", "haw" to "Hawaiian", "iw" to "Hebrew", "hi" to "Hindi",
        "hmn" to "Hmong", "hu" to "Hungarian", "is" to "Icelandic", "ig" to "Igbo",
        "ilo" to "Ilocano", "id" to "Indonesian", "ga" to "Irish", "it" to "Italian",
        "ja" to "Japanese", "jw" to "Javanese", "kn" to "Kannada", "kk" to "Kazakh",
        "km" to "Khmer", "rw" to "Kinyarwanda", "gom" to "Konkani", "ko" to "Korean",
        "kri" to "Krio", "ku" to "Kurdish (Kurmanji)", "ckb" to "Kurdish (Sorani)",
        "ky" to "Kyrgyz", "lo" to "Lao", "la" to "Latin", "lv" to "Latvian",
        "ln" to "Lingala", "lt" to "Lithuanian", "lg" to "Luganda", "lb" to "Luxembourgish",
        "mk" to "Macedonian", "mai" to "Maithili", "mg" to "Malagasy", "ms" to "Malay",
        "ml" to "Malayalam", "mt" to "Maltese", "mi" to "Maori", "mr" to "Marathi",
        "mni-Mtei" to "Meiteilon (Manipuri)", "lus" to "Mizo", "mn" to "Mongolian",
        "my" to "Myanmar (Burmese)", "ne" to "Nepali", "no" to "Norwegian", "or" to "Odia (Oriya)",
        "om" to "Oromo", "ps" to "Pashto", "fa" to "Persian", "pl" to "Polish",
        "pt" to "Portuguese", "pa" to "Punjabi", "qu" to "Quechua", "ro" to "Romanian",
        "ru" to "Russian", "sm" to "Samoan", "sa" to "Sanskrit", "gd" to "Scots Gaelic",
        "nso" to "Sepedi", "sr" to "Serbian", "st" to "Sesotho", "sn" to "Shona",
        "sd" to "Sindhi", "si" to "Sinhala", "sk" to "Slovak", "sl" to "Slovenian",
        "so" to "Somali", "es" to "Spanish", "su" to "Sundanese", "sw" to "Swahili",
        "sv" to "Swedish", "tg" to "Tajik", "ta" to "Tamil", "tt" to "Tatar",
        "te" to "Telugu", "th" to "Thai", "ti" to "Tigrinya", "ts" to "Tsonga",
        "tr" to "Turkish", "tk" to "Turkmen", "ak" to "Twi", "uk" to "Ukrainian",
        "ur" to "Urdu", "ug" to "Uyghur", "uz" to "Uzbek", "vi" to "Vietnamese",
        "cy" to "Welsh", "xh" to "Xhosa", "yi" to "Yiddish", "yo" to "Yoruba", "zu" to "Zulu"
    )

    private val VI = Locale("vi")

    private fun displayName(code: String, english: String): String {
        val tag = when (code) {
            "iw" -> "he"
            "jw" -> "jv"
            "zh-CN" -> return "Tiếng Trung (Giản thể)"
            "zh-TW" -> return "Tiếng Trung (Phồn thể)"
            else -> code
        }
        val localName = Locale.forLanguageTag(tag).getDisplayLanguage(VI)
        val name = if (localName.isNullOrBlank() || localName.equals(tag, true)) english
        else localName.replaceFirstChar { it.uppercase(VI) }
        return if (name == english) name else "$name ($english)"
    }

    /** Ngôn ngữ đích (không có tự động). */
    val targets: List<Language> by lazy {
        RAW.map { (c, n) -> Language(c, displayName(c, n)) }.sortedBy { it.name.lowercase(VI) }
    }

    /** Ngôn ngữ nguồn (có tự động phát hiện ở đầu). */
    fun sources(autoLabel: String): List<Language> = listOf(Language(AUTO, autoLabel)) + targets
}
