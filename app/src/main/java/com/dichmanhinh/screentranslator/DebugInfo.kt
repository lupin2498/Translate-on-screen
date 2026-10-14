package com.dichmanhinh.screentranslator

/** Trạng thái lần dịch gần nhất – dùng cho kiểm thử tự động và chẩn đoán lỗi. */
object DebugInfo {
    @Volatile var translateCalls = 0
    @Volatile var resultCount = 0
    @Volatile var lastBlocks = -1
    @Volatile var lastOriginals: List<String> = emptyList()
    @Volatile var lastTranslations: List<String> = emptyList()
    @Volatile var lastError: String? = null
    @Volatile var overlayShown = 0
    @Volatile var lastCapture: String? = null
    @Volatile var lastOcr: String? = null
    @Volatile var lastUnits: List<UnitInfo> = emptyList()

    /** Một đoạn chữ: ngôn ngữ nhận diện được, cách xử lý (dịch/bỏ qua) và bản dịch. */
    data class UnitInfo(val original: String, val lang: String, val action: String, val translated: String?)
}
