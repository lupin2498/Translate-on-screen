package com.dichmanhinh.screentranslator

import com.dichmanhinh.screentranslator.ai.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nhận ra file mô hình người dùng tự tải bằng trình duyệt rồi nhập vào ứng dụng. */
class ModelCatalogTest {

    private val gemma = ModelCatalog.GEMMA4_E2B

    @Test
    fun officialNamesMatch() {
        val m = ModelCatalog.matchFile("gemma-4-E2B-it-Q4_0.gguf", -1)!!
        assertEquals(gemma.id, m.first.id)
        assertEquals(gemma.model, m.second)
        val mm = ModelCatalog.matchFile("mmproj-gemma-4-E2B-it-Q8_0.gguf", -1)!!
        assertEquals(gemma.id, mm.first.id)
        assertEquals(gemma.mmproj, mm.second)
        assertEquals(ModelCatalog.HY_MT.model, ModelCatalog.matchFile("HY-MT1.5-1.8B-Q4_K_M.gguf", -1)!!.second)
    }

    @Test
    fun browserRenamedFilesMatch() {
        // Trình duyệt thêm " (1)" khi tải lại, hoặc đuôi ".bin"
        assertEquals(gemma.model, ModelCatalog.matchFile("gemma-4-E2B-it-Q4_0 (1).gguf", -1)!!.second)
        assertEquals(gemma.model, ModelCatalog.matchFile("gemma-4-E2B-it-Q4_0(2).gguf", -1)!!.second)
        assertEquals(gemma.model, ModelCatalog.matchFile("gemma-4-E2B-it-Q4_0.gguf.bin", -1)!!.second)
        assertEquals(gemma.mmproj, ModelCatalog.matchFile("MMPROJ-GEMMA-4-E2B-IT-Q8_0.GGUF", -1)!!.second)
        // Tên lạ nhưng đúng kích thước file chính thức
        assertEquals(gemma.mmproj, ModelCatalog.matchFile("download.bin", gemma.mmproj!!.size)!!.second)
    }

    @Test
    fun otherFilesDoNotMatch() {
        assertNull(ModelCatalog.matchFile("gemma-4-E2B-it-Q8_0.gguf", 4_967_497_152L))
        assertNull(ModelCatalog.matchFile("Qwen3-1.7B-Q4_K_M.gguf", 1_100_000_000L))
        // Q4_0 không được nhầm thành mmproj và ngược lại
        assertEquals(gemma.model, ModelCatalog.matchFile("gemma-4-E2B-it-Q4_0.gguf", -1)!!.second)
    }

    @Test
    fun mmprojNames() {
        assertTrue(ModelCatalog.isMmprojName("mmproj-foo.gguf"))
        assertTrue(ModelCatalog.isMmprojName("Qwen2.5-VL-3B-mmproj-f16.gguf"))
        assertFalse(ModelCatalog.isMmprojName("gemma-4-E2B-it-Q4_0.gguf"))
    }
}
