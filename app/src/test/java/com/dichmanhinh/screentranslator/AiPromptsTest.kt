package com.dichmanhinh.screentranslator

import com.dichmanhinh.screentranslator.ai.AiLang
import com.dichmanhinh.screentranslator.ai.AiPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptsTest {

    @Test
    fun parseTaggedInOrder() {
        val r = AiPrompts.parseTagged("<s1>Cài đặt</s1>\n<s2>Tài khoản và bảo mật</s2>", 2)
        assertEquals(listOf("Cài đặt", "Tài khoản và bảo mật"), r.toList())
    }

    @Test
    fun parseTaggedWithoutClosingTagsAndNoise() {
        val out = "Đây là bản dịch:\n<target><s2>Hai</s2> <s1>Một\n</s1><s3>Ba"
        val r = AiPrompts.parseTagged(out, 3)
        assertEquals(listOf("Một", "Hai", "Ba"), r.toList())
    }

    @Test
    fun parseTaggedMissingSegmentIsNull() {
        val r = AiPrompts.parseTagged("<s1>A</s1><s3>C</s3>", 3)
        assertEquals("A", r[0])
        assertNull(r[1])
        assertEquals("C", r[2])
    }

    @Test
    fun parseTaggedStripsThinking() {
        val r = AiPrompts.parseTagged("<think>suy nghĩ <s1>sai</s1></think><s1>Đúng</s1>", 1)
        assertEquals("Đúng", r[0])
        val g = AiPrompts.parseTagged("<|channel>thought\n<channel|><s1>Xin chào</s1>", 1)
        assertEquals("Xin chào", g[0])
    }

    @Test
    fun parseTaggedSingleUntagged() {
        assertEquals("Xin chào", AiPrompts.parseTagged("  Xin chào  ", 1)[0])
        assertNull(AiPrompts.parseTagged("Xin chào", 2)[0])
    }

    @Test
    fun parseTaggedIgnoresOutOfRangeAndDuplicates() {
        val r = AiPrompts.parseTagged("<s1>A</s1><s1>B</s1><s9>Z</s9>", 2)
        assertEquals("A", r[0])
        assertNull(r[1])
    }

    @Test
    fun parseSingle() {
        assertEquals("Chào bạn", AiPrompts.parseSingle("<target>Chào bạn</target>"))
        assertEquals("Chào bạn", AiPrompts.parseSingle("<s1>Chào bạn</s1>"))
        assertNull(AiPrompts.parseSingle("   "))
    }

    @Test
    fun parseReadImage() {
        val out = "<p><o>Привет</o><t>Xin chào</t></p>\n<p><o>Новые слова</o><t>Từ mới</t></p>\n<p><o>x</o><t></t></p>"
        val r = AiPrompts.parseReadImage(out)
        assertEquals(listOf("Привет" to "Xin chào", "Новые слова" to "Từ mới"), r)
        assertTrue(AiPrompts.parseReadImage("<none/>").isEmpty())
    }

    @Test
    fun packBatchesRespectsBudget() {
        val segs = List(10) { "这是一个比较长的句子，用来测试分批。" } // ~18 token mỗi đoạn
        val batches = AiPrompts.packBatches(segs, 60)
        assertTrue(batches.size > 1)
        assertEquals((0 until 10).toList(), batches.flatten())
        for (b in batches) assertTrue(b.sumOf { AiPrompts.estimateTokens(segs[it]) + 6 } <= 60 || b.size == 1)
    }

    @Test
    fun looksBroken() {
        assertFalse(AiPrompts.looksBroken("你好", "Xin chào"))
        assertTrue(AiPrompts.looksBroken("你好", "xin chào ".repeat(60)))
        assertTrue(AiPrompts.looksBroken("Hello world, how are you today my friend", List(40) { "là" }.joinToString(" ")))
    }

    @Test
    fun languageNames() {
        assertEquals("Vietnamese", AiLang.english("vi"))
        assertEquals("Simplified Chinese", AiLang.english("zh-CN"))
        assertEquals("Russian", AiLang.english("ru"))
        assertEquals("越南语", AiLang.chinese("vi"))
        assertEquals("希伯来语", AiLang.chinese("iw"))
        assertTrue(AiLang.hyMtSupports("vi"))
        assertTrue(AiLang.isChinese("zh-TW"))
    }

    @Test
    fun prompts() {
        val single = AiPrompts.hyMtSingle("Hello", "en", "vi")
        assertTrue(single.startsWith("Translate the following segment into Vietnamese"))
        val zh = AiPrompts.hyMtSingle("你好", "zh-CN", "vi")
        assertTrue(zh.startsWith("将以下文本翻译为越南语"))
        assertTrue(zh.endsWith("\n\n你好"))
        // không rõ nguồn nhưng là chữ Hán -> mẫu tiếng Trung; tiếng Nhật -> mẫu tiếng Anh
        assertTrue(AiPrompts.hyMtSingle("账号与安全", "auto", "vi").startsWith("将以下文本"))
        assertTrue(AiPrompts.hyMtSingle("ログインして", "auto", "vi").startsWith("Translate"))
        assertTrue(AiPrompts.hyMtSingle("账号\n与安全", "zh-CN", "vi").endsWith("账号 与安全"))
        val u = AiPrompts.generalUser(listOf("a", "b"), listOf("ru", "ru"), "vi", "<__media__>", null)
        assertTrue(u.startsWith("<__media__>\n"))
        assertTrue(u.contains("Russian"))
        assertTrue(u.contains("<s2>b</s2>"))
        assertTrue(AiPrompts.generalSystem("vi", true).contains("Hán Việt"))
    }
}
