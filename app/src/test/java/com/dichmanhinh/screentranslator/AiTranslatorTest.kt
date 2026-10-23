package com.dichmanhinh.screentranslator

import com.dichmanhinh.screentranslator.ai.AiException
import com.dichmanhinh.screentranslator.ai.AiImage
import com.dichmanhinh.screentranslator.ai.AiTranslator
import com.dichmanhinh.screentranslator.ai.LlmBackend
import com.dichmanhinh.screentranslator.ai.PromptStyle
import com.dichmanhinh.screentranslator.ai.Sampling
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiTranslatorTest {

    private class Fake(
        override val style: PromptStyle,
        override val hasVision: Boolean = false,
        val reply: (system: String?, user: String, image: AiImage?) -> String,
    ) : LlmBackend {
        override val mediaMarker = "<__media__>"
        override val sampling = Sampling(0f, 1, 1f)
        override val contextSize = 4096
        val calls = ArrayList<Triple<String?, String, AiImage?>>()
        override suspend fun generate(system: String?, user: String, image: AiImage?, maxTokens: Int): String {
            calls.add(Triple(system, user, image))
            return reply(system, user, image)
        }
    }

    /** Dịch giả: "<sN>x</sN>" -> "<sN>VI(x)</sN>" cho mọi thẻ trong prompt. */
    private fun echoTags(user: String): String =
        Regex("<s(\\d+)>(.*?)</s\\1>").findAll(user).joinToString("\n") {
            "<s${it.groupValues[1]}>VI(${it.groupValues[2]})</s${it.groupValues[1]}>"
        }

    @Test
    fun generalTranslatesWholeScreenInOneCall() = runBlocking {
        val llm = Fake(PromptStyle.GENERAL) { _, u, _ -> echoTags(u) }
        val r = AiTranslator(llm).translate(listOf("设置", "账号与安全", "退出"), listOf("zh-CN", "zh-CN", "zh-CN"), "vi")
        assertEquals(listOf("VI(设置)", "VI(账号与安全)", "VI(退出)"), r)
        assertEquals(1, llm.calls.size)
        assertTrue(llm.calls[0].first!!.contains("Vietnamese"))
    }

    @Test
    fun missingSegmentIsRetriedAlone() = runBlocking {
        val llm = Fake(PromptStyle.GENERAL) { _, u, _ ->
            if (u.contains("<s2>")) "<s1>Một</s1>\n<s3>Ba</s3>" else "<s1>Hai</s1>"
        }
        val r = AiTranslator(llm).translate(listOf("one", "two", "three"), listOf("en", "en", "en"), "vi")
        assertEquals(listOf("Một", "Hai", "Ba"), r)
        assertEquals(2, llm.calls.size)
        assertTrue("retry has context", llm.calls[1].second.contains("one"))
    }

    @Test
    fun hyMtTranslatesEachSegmentWithOfficialTemplate() = runBlocking {
        val llm = Fake(PromptStyle.HY_MT) { _, u, _ -> "VI(" + u.substringAfterLast("\n") + ")" }
        val one = AiTranslator(llm).translate(listOf("Hello"), listOf("en"), "vi")
        assertEquals(listOf("VI(Hello)"), one)
        assertNull("HY-MT has no system prompt", llm.calls[0].first)
        assertTrue(llm.calls[0].second.startsWith("Translate the following segment into Vietnamese"))

        llm.calls.clear()
        val many = AiTranslator(llm).translate(listOf("设置", "退出", "确定"), listOf("zh-CN", "zh-CN", "zh-CN"), "vi")
        assertEquals(listOf("VI(设置)", "VI(退出)", "VI(确定)"), many)
        assertEquals("one call per segment", 3, llm.calls.size)
        for (c in llm.calls) {
            assertTrue(c.second.startsWith("将以下文本翻译为越南语"))
            assertTrue("no other segment leaks into the prompt", c.second.count { it == '\n' } == 2)
        }
    }

    @Test
    fun imageOnlyForVisionModels() = runBlocking {
        val img = AiImage(ByteArray(16 * 16 * 3), 16, 16)
        val blind = Fake(PromptStyle.GENERAL, hasVision = false) { _, u, _ -> echoTags(u) }
        AiTranslator(blind).translate(listOf("a", "b"), listOf("en", "en"), "vi", img)
        assertNull(blind.calls[0].third)
        assertTrue(!blind.calls[0].second.contains("<__media__>"))

        val seeing = Fake(PromptStyle.GENERAL, hasVision = true) { _, u, _ -> echoTags(u) }
        AiTranslator(seeing).translate(listOf("a", "b"), listOf("en", "en"), "vi", img)
        assertNotNull(seeing.calls[0].third)
        assertTrue(seeing.calls[0].second.startsWith("<__media__>"))
    }

    @Test
    fun brokenOutputIsRejected() = runBlocking {
        val llm = Fake(PromptStyle.GENERAL) { _, u, _ ->
            if (u.contains("<s2>")) "<s1>${"lặp ".repeat(200)}</s1><s2>Tốt</s2>" else "<s1>Ổn</s1>"
        }
        val r = AiTranslator(llm).translate(listOf("hi", "good"), listOf("en", "en"), "vi")
        assertEquals(listOf("Ổn", "Tốt"), r)
    }

    @Test
    fun allFailedThrows() {
        val llm = Fake(PromptStyle.GENERAL) { _, _, _ -> throw AiException("boom") }
        try {
            runBlocking { AiTranslator(llm).translate(listOf("a"), listOf("en"), "vi") }
            fail("expected exception")
        } catch (e: AiException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun largeScreenIsSplitWithContext() = runBlocking {
        val segs = List(120) { "Đoạn văn bản số $it khá dài để chia thành nhiều lô khác nhau khi dịch." }
        val llm = Fake(PromptStyle.GENERAL) { _, u, _ -> echoTags(u) }
        val r = AiTranslator(llm).translate(segs, segs.map { "vi" }, "en")
        assertTrue(llm.calls.size > 1)
        assertTrue(r.all { it != null })
        assertTrue(llm.calls[1].second.contains("context only"))
    }

    @Test
    fun readImageNeedsVision() = runBlocking {
        val img = AiImage(ByteArray(16 * 16 * 3), 16, 16)
        val llm = Fake(PromptStyle.GENERAL, hasVision = true) { _, _, _ -> "<p><o>Привет</o><t>Xin chào</t></p>" }
        assertEquals(listOf("Привет" to "Xin chào"), AiTranslator(llm).readImage(img, "vi"))
        try {
            AiTranslator(Fake(PromptStyle.GENERAL) { _, _, _ -> "" }).readImage(img, "vi")
            fail("expected exception")
        } catch (e: AiException) {
            // ok
        }
    }
}
