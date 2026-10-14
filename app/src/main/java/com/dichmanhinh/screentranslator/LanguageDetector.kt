package com.dichmanhinh.screentranslator

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Nhận diện ngôn ngữ của từng đoạn chữ ngay trên máy (ML Kit Language ID, không cần mạng). */
class LanguageDetector {
    private val client by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
        )
    }
    private val scorer by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build()
        )
    }

    /** Mã ngôn ngữ BCP-47 của ML Kit, "und" nếu không chắc. Chữ Hán/kana/hangul xét theo chữ viết. */
    suspend fun detect(text: String): String {
        LangRouting.scriptLanguage(text)?.let { return it }
        return try {
            client.identifyLanguage(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LangRouting.UNDETERMINED
        }
    }

    /**
     * Mức độ đoạn chữ "giống một ngôn ngữ thật" (0..1). Chữ OCR đọc sai (chữ rác) cho điểm thấp.
     * Dùng để chọn giữa hai cách đọc của hai bộ OCR khác nhau.
     */
    suspend fun score(text: String): Float {
        return try {
            scorer.identifyPossibleLanguages(text).await()
                .filter { it.languageTag != LangRouting.UNDETERMINED }
                .maxOfOrNull { it.confidence } ?: 0f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0f
        }
    }

    fun close() {
        runCatching { client.close() }
        runCatching { scorer.close() }
    }
}
