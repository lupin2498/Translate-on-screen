package com.dichmanhinh.screentranslator

/** Trạng thái lần dịch gần nhất – dùng cho kiểm thử tự động và chẩn đoán lỗi. */
object DebugInfo {
    @Volatile var translateCalls = 0
    @Volatile var resultCount = 0
    @Volatile var lastBlocks = -1
    @Volatile var lastOriginals: List<String> = emptyList()
    @Volatile var lastTranslations: List<String> = emptyList()
    @Volatile var lastError: String? = null
}
