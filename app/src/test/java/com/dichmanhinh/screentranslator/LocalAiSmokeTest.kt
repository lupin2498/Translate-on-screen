package com.dichmanhinh.screentranslator

import com.dichmanhinh.screentranslator.ai.AiImage
import com.dichmanhinh.screentranslator.ai.AiTranslator
import com.dichmanhinh.screentranslator.ai.LlamaNative
import com.dichmanhinh.screentranslator.ai.ModelCatalog
import com.dichmanhinh.screentranslator.ai.ModelSpec
import com.dichmanhinh.screentranslator.ai.NativeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Kiểm thử AI trên máy với mô hình thật – chạy trên máy tính (CI) bằng thư viện libscreenai.so
 * dựng cho máy tính. Tự bỏ qua khi không có biến môi trường:
 *   SCREENAI_LIB      đường dẫn libscreenai.so
 *   SCREENAI_HYMT     file GGUF HY-MT 1.5
 *   SCREENAI_GEMMA    file GGUF Gemma 4 E2B, SCREENAI_GEMMA_MMPROJ = mmproj tương ứng
 *   SCREENAI_IMAGES   thư mục ảnh thử (video_text_ru.jpg, video_table_zh.jpg)
 * Kết quả ghi vào build/ai-smoke/<mô hình>.md.
 */
class LocalAiSmokeTest {

    private data class Case(val name: String, val segments: List<String>, val sources: List<String>)

    private val cases = listOf(
        Case(
            "Giao diện + phụ đề tiếng Trung",
            listOf("设置", "账号与安全", "我们明天去北京看李老师吧。", "他说你不用担心，一切都会好起来的。", "确定", "取消"),
            List(6) { "zh-CN" }
        ),
        Case(
            "Tiếng Nga (có chữ OCR đọc nhầm La-tinh)",
            listOf("Пpивeт, кaк дeлa?", "Сегодня мы будем изучать новые слова", "Нажмите кнопку «Продолжить»"),
            List(3) { "ru" }
        ),
        Case(
            "Tiếng Anh có lỗi OCR",
            listOf("Tap here to contiune", "Your account has been successfuly verifed", "Download the latest verslon of the app", "Settings"),
            List(4) { "en" }
        ),
        Case(
            "Nhật + Hàn",
            listOf("今日は天気がいいですね。", "ログイン", "안녕하세요, 만나서 반갑습니다."),
            listOf("ja", "ja", "ko")
        ),
        Case(
            "Hội thoại nhiều câu (ngữ cảnh, xưng hô)",
            listOf("哥哥，你什么时候回来？", "我下个月就回家了，你要好好照顾妈妈。", "知道了，你也要注意身体！"),
            List(3) { "zh-CN" }
        ),
    )

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun loadLib(): Boolean {
        val lib = env("SCREENAI_LIB") ?: return false
        return LlamaNative.loadFrom(lib)
    }

    private fun image(name: String): AiImage? {
        val dir = env("SCREENAI_IMAGES") ?: return null
        val f = File(dir, name)
        if (!f.isFile) return null
        // javax.imageio không có trong classpath biên dịch của unit test Android -> gọi qua reflection
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)
            ?: return null
        val c = img.javaClass
        val w = c.getMethod("getWidth").invoke(img) as Int
        val h = c.getMethod("getHeight").invoke(img) as Int
        val int = Int::class.javaPrimitiveType
        val px = c.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
            .invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        val rgb = ByteArray(w * h * 3)
        var j = 0
        for (p in px) {
            rgb[j++] = (p shr 16 and 0xFF).toByte()
            rgb[j++] = (p shr 8 and 0xFF).toByte()
            rgb[j++] = (p and 0xFF).toByte()
        }
        return AiImage(rgb, w, h)
    }

    private fun run(spec: ModelSpec, model: String, mmproj: String?, perSegment: Boolean = false, tag: String = "") = runBlocking {
        val report = StringBuilder("# ${spec.name} $tag\n\n")
        val t0 = System.currentTimeMillis()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        NativeBackend.load(spec, model, mmproj, threads, null).use { ai ->
            report.append("Nạp: ${System.currentTimeMillis() - t0} ms · ${ai.info} · vision=${ai.hasVision}\n\n")
            var total = 0
            var ok = 0
            for (c in cases) {
                val tr = AiTranslator(ai, perSegment)
                val s = System.currentTimeMillis()
                val res = tr.translate(c.segments, c.sources, "vi")
                val ms = System.currentTimeMillis() - s
                report.append("## ${c.name} — ${ms} ms, ${tr.lastCalls} lượt gọi, ${ai.lastStats}\n\n")
                c.segments.forEachIndexed { i, src ->
                    report.append("- `$src` → ${res[i] ?: "**(không dịch được)**"}\n")
                }
                report.append("\n")
                total += c.segments.size
                ok += res.count { it != null }
            }
            if (ai.hasVision && tag.isEmpty()) {
                for (name in listOf("video_text_ru.jpg", "video_table_zh.jpg")) {
                    val img = image(name) ?: continue
                    val s = System.currentTimeMillis()
                    val pairs = AiTranslator(ai).readImage(img, "vi")
                    report.append("## Đọc ảnh $name (${img.width}x${img.height}) — ${System.currentTimeMillis() - s} ms, ${ai.lastStats}\n\n")
                    pairs.forEach { (o, t) -> report.append("- `$o` → $t\n") }
                    report.append("\n")
                    // Đọc ảnh + sửa chữ OCR: gửi kèm ảnh vài đoạn OCR cố tình sai
                    val ocr = if (name.contains("ru")) listOf("Ceгoдня", "нoвые cлoвa") else listOf("学习", "单词")
                    val s2 = System.currentTimeMillis()
                    val fixed = AiTranslator(ai).translate(ocr, ocr.map { "auto" }, "vi", img)
                    report.append("Kèm ảnh, OCR sai ${ocr} → ${fixed} (${System.currentTimeMillis() - s2} ms)\n\n")
                }
            }
            val out = File("build/ai-smoke").apply { mkdirs() }
            File(out, "${spec.id}${tag.replace(' ', '_')}.md").writeText(report.toString())
            println(report)
            assertTrue("dịch được $ok/$total đoạn", ok >= total * 0.8)
        }
    }

    @Test
    fun hyMt() {
        val model = env("SCREENAI_HYMT")
        assumeTrue("không có mô hình HY-MT", model != null && loadLib())
        run(ModelCatalog.HY_MT, model!!, null)
    }

    /** So sánh với bản lượng tử hóa Q8_0 (chỉ khi có SCREENAI_HYMT_Q8). */
    @Test
    fun hyMtQ8() {
        val q8 = env("SCREENAI_HYMT_Q8")
        assumeTrue("không có bản Q8_0", q8 != null && loadLib())
        run(ModelCatalog.HY_MT, q8!!, null, tag = "Q8_0")
    }

    @Test
    fun gemma4() {
        val model = env("SCREENAI_GEMMA")
        assumeTrue("không có mô hình Gemma", model != null && loadLib())
        run(ModelCatalog.GEMMA4_E2B, model!!, env("SCREENAI_GEMMA_MMPROJ"))
    }
}
