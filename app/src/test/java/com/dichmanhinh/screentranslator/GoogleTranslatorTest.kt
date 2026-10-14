package com.dichmanhinh.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class GoogleTranslatorTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").length

    @Test
    fun batchesStayUnderGetLimit() {
        val texts = List(40) { "不是 不對 沒有 謝謝 可以 不要 不想 真的 不用 不行 $it" }
        val batches = GoogleTranslator.packBatches(texts.indices.toList(), texts, GoogleTranslator.MAX_Q)
        assertEquals(texts.indices.toList(), batches.flatten())
        for (b in batches) {
            val q = b.joinToString("\n") { texts[it] }
            assertTrue("batch too long: ${enc(q)}", enc(q) <= GoogleTranslator.MAX_Q)
        }
        assertTrue(batches.size > 1)
    }

    @Test
    fun longTextIsSplitAtSentences() {
        val sentence = "Николай Губенко готовился жениться на актрисе Марианне Вертинской. "
        val text = sentence.repeat(20).trim()
        val parts = GoogleTranslator.splitForRequest(text, GoogleTranslator.MAX_Q)
        assertTrue(parts.size > 1)
        for (p in parts) {
            assertTrue(enc(p) <= GoogleTranslator.MAX_Q)
            assertTrue("must end at a sentence boundary: $p", p.trimEnd().endsWith("."))
        }
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun hugeSentenceIsHardSplit() {
        val text = "слово ".repeat(800).trim()
        val parts = GoogleTranslator.splitForRequest(text, GoogleTranslator.MAX_Q)
        for (p in parts) assertTrue(enc(p) <= GoogleTranslator.MAX_Q)
        assertEquals(800, parts.sumOf { p -> p.split(" ").count { it.isNotBlank() } })
    }

    @Test
    fun shortTextIsSingleRequest() {
        assertEquals(listOf("Hello world"), GoogleTranslator.splitForRequest("Hello world", GoogleTranslator.MAX_Q))
    }
}
