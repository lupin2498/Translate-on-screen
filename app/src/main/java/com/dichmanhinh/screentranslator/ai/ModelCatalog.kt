package com.dichmanhinh.screentranslator.ai

import java.util.Locale

/** Ảnh RGB 8-bit (3 byte/điểm ảnh) đưa cho mô hình đọc ảnh. */
class AiImage(val rgb: ByteArray, val width: Int, val height: Int) {
    init {
        require(rgb.size >= width * height * 3) { "rgb buffer too small" }
    }
}

/** Tham số lấy mẫu khi sinh chữ. temperature <= 0: luôn chọn token có xác suất cao nhất. */
data class Sampling(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1f,
)

/** Cách viết prompt phù hợp với từng loại mô hình. */
enum class PromptStyle {
    /** Mô hình chuyên dịch Hunyuan-MT (Tencent): dùng đúng mẫu prompt mà mô hình được huấn luyện. */
    HY_MT,

    /** Mô hình trò chuyện đa năng (Gemma, Qwen...): sửa lỗi OCR theo ngữ cảnh rồi dịch. */
    GENERAL,
}

data class ModelFile(val name: String, val url: String, val size: Long)

data class ModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val model: ModelFile,
    /** Bộ chiếu ảnh (mmproj) – có thì mô hình nhìn được ảnh chụp màn hình. */
    val mmproj: ModelFile?,
    val style: PromptStyle,
    val nCtx: Int,
    val minRamGb: Int,
    val sampling: Sampling,
    val custom: Boolean = false,
) {
    val files: List<ModelFile> get() = listOfNotNull(model, mmproj)
    val totalSize: Long get() = files.sumOf { it.size }
    val vision: Boolean get() = mmproj != null
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"

    /** Tencent Hunyuan-MT 1.5 1.8B: chuyên dịch, 33 ngôn ngữ, nhỏ và nhanh. */
    val HY_MT = ModelSpec(
        id = "hy-mt1.5-1.8b-q4km",
        name = "HY-MT 1.5 · 1.8B (Tencent)",
        description = "Mô hình chuyên dịch thuật, 33 ngôn ngữ (Việt, Trung, Anh, Nga, Nhật, Hàn, Thái, " +
            "Pháp, Đức…). Nhẹ và nhanh nhất, dịch tự nhiên. Không nhìn ảnh.",
        model = ModelFile(
            "HY-MT1.5-1.8B-Q4_K_M.gguf",
            "$HF/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf",
            1_133_080_512L
        ),
        mmproj = null,
        style = PromptStyle.HY_MT,
        nCtx = 4096,
        minRamGb = 4,
        // Giải mã tham lam (chính xác và ổn định giữa các lần chạm hơn lấy mẫu ngẫu nhiên khi thử
        // với mô hình thật), giữ repetition_penalty 1.05 theo khuyến nghị của nhà phát triển.
        sampling = Sampling(temperature = 0f, topK = 20, topP = 0.6f, repeatPenalty = 1.05f),
    )

    /** Google Gemma 4 E2B: đa năng, nhìn được ảnh để đọc lại chữ OCR sai/bỏ sót. */
    val GEMMA4_E2B = ModelSpec(
        id = "gemma-4-e2b-q4",
        name = "Gemma 4 E2B (Google) · đọc ảnh",
        description = "AI đa năng nhìn được ảnh chụp: tự sửa chữ OCR đọc sai, đọc cả chữ OCR bỏ sót " +
            "(chữ nghệ thuật, truyện tranh), dịch theo ngữ cảnh cả màn hình. Hơn 140 ngôn ngữ. " +
            "Nặng hơn, cần máy RAM từ 6–8 GB.",
        model = ModelFile(
            "gemma-4-E2B-it-Q4_0.gguf",
            "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
            2_841_481_184L
        ),
        mmproj = ModelFile(
            "mmproj-gemma-4-E2B-it-Q8_0.gguf",
            "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-Q8_0.gguf",
            557_368_064L
        ),
        style = PromptStyle.GENERAL,
        nCtx = 6144,
        minRamGb = 6,
        sampling = Sampling(temperature = 0.2f, topK = 40, topP = 0.9f, repeatPenalty = 1.0f),
    )

    val builtIn: List<ModelSpec> = listOf(HY_MT, GEMMA4_E2B)

    const val CUSTOM_PREFIX = "custom:"

    /** Mô hình GGUF người dùng tự nhập. */
    fun custom(fileName: String, size: Long): ModelSpec {
        val hy = fileName.contains("hy-mt", ignoreCase = true) || fileName.contains("hunyuan-mt", ignoreCase = true)
        return ModelSpec(
            id = CUSTOM_PREFIX + fileName,
            name = fileName.removeSuffix(".gguf"),
            description = "Mô hình GGUF tự nhập",
            model = ModelFile(fileName, "", size),
            mmproj = null,
            style = if (hy) PromptStyle.HY_MT else PromptStyle.GENERAL,
            nCtx = 4096,
            minRamGb = 4,
            sampling = if (hy) HY_MT.sampling else Sampling(0.2f, 40, 0.9f),
            custom = true,
        )
    }

    fun byId(id: String?): ModelSpec? = builtIn.firstOrNull { it.id == id }

    /**
     * File trong danh mục ứng với file người dùng tự tải rồi nhập vào: trùng tên (kể cả khi trình
     * duyệt thêm " (1)" hoặc đuôi ".bin") hoặc trùng đúng kích thước.
     */
    fun matchFile(displayName: String, size: Long): Pair<ModelSpec, ModelFile>? {
        val n = displayName.trim().lowercase(Locale.ROOT).replace(Regex("\\s*\\(\\d+\\)"), "")
        val all = builtIn.flatMap { s -> s.files.map { s to it } }
        fun base(f: ModelFile) = f.name.lowercase(Locale.ROOT).removeSuffix(".gguf")
        return all.firstOrNull { it.second.name.lowercase(Locale.ROOT) == n }
            ?: all.firstOrNull { size > 0 && it.second.size == size }
            ?: all.firstOrNull { n.startsWith(base(it.second) + ".") || n == base(it.second) }
    }

    /** File bộ chiếu ảnh (mmproj), không phải mô hình ngôn ngữ. */
    fun isMmprojName(name: String): Boolean = name.lowercase(Locale.ROOT).let {
        it.startsWith("mmproj") || it.contains("-mmproj") || it.contains("_mmproj")
    }
}
