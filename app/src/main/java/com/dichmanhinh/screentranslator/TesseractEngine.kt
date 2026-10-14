package com.dichmanhinh.screentranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Một dòng chữ do Tesseract đọc, kèm độ tin cậy 0..100 và số thứ tự khối văn bản chứa nó. */
data class TessLine(val line: TextLine, val confidence: Float, val block: Int)

/**
 * OCR bằng Tesseract cho các bộ chữ mà ML Kit không hỗ trợ: Kirin (Nga, Ukraina...), Hy Lạp,
 * Ả Rập, Do Thái, Thái, Lào, Khmer, Myanmar, Gruzia, Armenia, các chữ Ấn Độ ngoài Devanagari...
 * Dữ liệu ngôn ngữ (tessdata_fast, 1–10 MB mỗi ngôn ngữ) được tải ở lần dùng đầu tiên.
 */
class TesseractEngine(context: Context) {
    companion object {
        private const val TAG = "ScreenTranslator"
        private const val URL_BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"

        /** Mã Google Dịch -> mã dữ liệu Tesseract (chỉ các ngôn ngữ ML Kit không đọc được). */
        private val TESS = mapOf(
            "ru" to "rus", "uk" to "ukr", "be" to "bel", "bg" to "bul", "sr" to "srp", "mk" to "mkd",
            "kk" to "kaz", "ky" to "kir", "mn" to "mon", "tg" to "tgk", "tt" to "tat",
            "el" to "ell", "ar" to "ara", "fa" to "fas", "ur" to "urd", "ps" to "pus", "sd" to "snd",
            "ug" to "uig", "iw" to "heb", "yi" to "yid", "th" to "tha", "lo" to "lao", "km" to "khm",
            "my" to "mya", "ka" to "kat", "hy" to "hye", "am" to "amh", "ti" to "tir", "si" to "sin",
            "ta" to "tam", "te" to "tel", "kn" to "kan", "ml" to "mal", "gu" to "guj", "pa" to "pan",
            "or" to "ori", "bn" to "ben", "as" to "asm", "dv" to "div",
        )

        fun tessLangFor(googleCode: String): String? = TESS[googleCode]

        fun googleCodeFor(tessLang: String): String? = TESS.entries.firstOrNull { it.value == tessLang }?.key
    }

    private val appContext = context.applicationContext
    private val dataDir by lazy { File(appContext.filesDir, "tesseract") }
    private val mutex = Mutex()
    private var api: TessBaseAPI? = null
    private var apiLang: String? = null

    private fun dataFile(lang: String) = File(File(dataDir, "tessdata"), "$lang.traineddata")

    fun hasData(lang: String): Boolean = dataFile(lang).let { it.exists() && it.length() > 100_000 }

    /** Tải dữ liệu ngôn ngữ nếu chưa có. [onDownload] được gọi trước khi bắt đầu tải. */
    suspend fun ensureData(lang: String, onDownload: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (hasData(lang)) return@withContext true
        onDownload(lang)
        val target = dataFile(lang)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$lang.traineddata.part")
        try {
            val conn = URL("$URL_BASE$lang.traineddata").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() < 100_000) throw java.io.IOException("file too small")
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "tessdata download failed: $lang", e)
            tmp.delete()
            false
        }
    }

    /**
     * Đọc các dòng chữ trong [region] (null = cả ảnh). Trả về rỗng nếu không có dữ liệu ngôn ngữ.
     * Tesseract không an toàn đa luồng nên mọi lệnh gọi được tuần tự hóa.
     */
    suspend fun recognize(
        bitmap: Bitmap, lang: String, region: Rect?, onDownload: (String) -> Unit
    ): List<TessLine> {
        if (!ensureData(lang, onDownload)) return emptyList()
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val tess = obtain(lang) ?: return@withContext emptyList()
                val out = ArrayList<TessLine>()
                try {
                    tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                    tess.setImage(bitmap)
                    if (region != null) tess.setRectangle(region)
                    tess.getUTF8Text() // chạy nhận dạng
                    val iter = tess.getResultIterator() ?: return@withContext out
                    try {
                        iter.begin()
                        var block = -1
                        do {
                            if (block < 0 || iter.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) block++
                            val text = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                            if (text.isNullOrEmpty()) continue
                            val conf = iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                            val b = iter.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                            // [left, top, right, bottom]; phòng trường hợp trả về [x, y, w, h]
                            val r = if (b[2] > b[0] && b[3] > b[1]) Rect(b[0], b[1], b[2], b[3])
                            else Rect(b[0], b[1], b[0] + b[2], b[1] + b[3])
                            out.add(TessLine(TextLine(text, r.left, r.top, r.right, r.bottom), conf, block))
                        } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    } finally {
                        iter.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "tesseract failed", e)
                } finally {
                    runCatching { tess.clear() }
                }
                out
            }
        }
    }

    private fun obtain(lang: String): TessBaseAPI? {
        api?.let { if (apiLang == lang) return it }
        api?.recycle()
        api = null
        val t = TessBaseAPI()
        if (!t.init(dataDir.absolutePath, lang, TessBaseAPI.OEM_LSTM_ONLY)) {
            Log.e(TAG, "tesseract init failed: $lang")
            t.recycle()
            return null
        }
        api = t
        apiLang = lang
        return t
    }

    fun close() {
        runCatching { api?.recycle() }
        api = null
    }
}
