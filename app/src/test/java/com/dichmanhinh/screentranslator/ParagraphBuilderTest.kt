package com.dichmanhinh.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphBuilderTest {

    /** Chú thích Instagram (ảnh người dùng gửi): mỗi dòng xuống hàng giữa câu. */
    private val caption = listOf(
        "zosterday \"Nom complet: Gazo" to 620,
        "En mélangeant une drill sombre, des" to 713,
        "productions puissantes et" to 522,
        "une manière de rapper immédiatement" to 760,
        "reconnaissable, Gazo" to 433,
        "s'est imposé comme l'une des figures" to 733,
        "majeures de la scène" to 425,
        "urbaine francophone. Ses morceaux" to 711,
        "accumulent régulièrement" to 524,
        "des millions d'écoutes sur les plateformes" to 818,
        "de streaming, tandis" to 415,
        "que son univers visuel et son identité" to 733,
        "musicale continuent" to 410,
        "d'attirer un public toujours plus large." to 738,
        "Situation en 2026 : Au milieu de l'année" to 774,
        "2026, Gazo poursuit" to 413,
        "son évolution avec de nouveaux projets et" to 829,
        "plusieurs" to 202,
        "collaborations remarquées. Ses sorties" to 767,
        "figurent régulièrement" to 454,
        "parmi les plus écoutées, alors que sa" to 724,
        "popularité sur les réseaux" to 517,
        "sociaux ne cesse de grandir. Grâce à sa" to 769,
        "constance et à son" to 385,
        "influence dans la drill moderne, il reste" to 754,
        "considéré comme l'un" to 441,
        "des artistes les plus importants de sa" to 735,
        "génération. \"—" to 305,
    ).mapIndexed { i, (t, right) -> TextLine(t, 32, 165 + i * 54, right, 205 + i * 54) }

    private val uiLines = listOf(
        TextLine("Q214K", 30, 80, 190, 120),
        TextLine("Q2.983", 215, 80, 390, 120),
        TextLine("5.274V", 500, 80, 600, 120),
        TextLine("23 tháng 9 • Xem bản dịch", 32, 1690, 460, 1724),
        TextLine("Theo dõi", 585, 1815, 750, 1855),
    )

    @Test
    fun captionIsGroupedIntoWholeSentences() {
        val paras = ParagraphBuilder.build(caption + uiLines)
        val texts = paras.map { it.text }
        assertTrue(texts.contains("zosterday \"Nom complet: Gazo"))
        val p2 = paras.first { it.text.startsWith("En mélangeant") }
        assertEquals(13, p2.lines)
        assertTrue(p2.text.endsWith("un public toujours plus large."))
        assertTrue(p2.text.contains("Ses morceaux accumulent régulièrement des millions"))
        val p3 = paras.first { it.text.startsWith("Situation en 2026") }
        assertEquals(14, p3.lines)
        assertTrue(p3.text.contains("sur les réseaux sociaux ne cesse"))
        // Dòng giao diện không bị ghép vào đoạn văn
        assertTrue(texts.contains("23 tháng 9 • Xem bản dịch"))
        assertTrue(texts.contains("Theo dõi"))
        assertTrue(texts.contains("Q214K"))
        assertEquals(3 + uiLines.size, paras.size)
    }

    /**
     * Tái hiện kết quả OCR thật trên emulator: dòng không có nét thấp có khung thấp hơn
     * (28px so với 36px) nên khoảng cách trông lớn hơn; OCR đọc nhầm "sociaux" thành "SOciaux".
     */
    @Test
    fun realOcrQuirksStillMerge() {
        val lines = listOf(
            TextLine("que son univers visuel et son identité", 189, 1000, 1000, 1036),
            TextLine("musicale continuent", 189, 1060, 600, 1088), // không có nét thấp
            TextLine("d'attirer un public toujours plus large.", 189, 1112, 980, 1148),
            TextLine("Situation en 2026: Au milieu de l'année", 189, 1164, 990, 1200),
            TextLine("alors que sa popularité sur les réseaux", 189, 1216, 1010, 1252),
            TextLine("SOciaux ne cesse de grandir.", 189, 1268, 760, 1304),
        )
        val paras = ParagraphBuilder.build(lines)
        assertEquals(paras.joinToString("\n") { it.text }, 2, paras.size)
        assertEquals(
            "que son univers visuel et son identité musicale continuent d'attirer un public toujours plus large.",
            paras[0].text
        )
        assertTrue(paras[1].text, paras[1].text.endsWith("sur les réseaux sociaux ne cesse de grandir."))
    }

    @Test
    fun fixOcrCase() {
        assertEquals("les réseaux sociaux ne", ParagraphBuilder.fixOcrCase("les réseaux SOciaux ne"))
        assertEquals("Fin. Sociaux", ParagraphBuilder.fixOcrCase("Fin. SOciaux"))
        assertEquals("État", ParagraphBuilder.fixOcrCase("ÉTat"))
        assertEquals("NASA USA OK", ParagraphBuilder.fixOcrCase("NASA USA OK"))
        assertEquals("McDonald iPhone", ParagraphBuilder.fixOcrCase("McDonald iPhone"))
    }

    @Test
    fun separatedLinesStaySeparate() {
        // Các dòng cách xa nhau (mỗi dòng một mục) không được ghép
        val lines = listOf("Hello world", "Good morning", "Thank you very much")
            .mapIndexed { i, t -> TextLine(t, 190, 400 + i * 160, 900, 460 + i * 160) }
        assertEquals(3, ParagraphBuilder.build(lines).size)
    }

    @Test
    fun sideBySideColumnsAreNotMerged() {
        val lines = listOf(
            TextLine("left column first line", 20, 100, 500, 140),
            TextLine("right column first line", 560, 100, 1060, 140),
            TextLine("left column second line", 20, 154, 500, 194),
            TextLine("right column second line", 560, 154, 1060, 194),
        )
        val paras = ParagraphBuilder.build(lines)
        assertEquals(2, paras.size)
        assertEquals("left column first line left column second line", paras[0].text)
        assertEquals("right column first line right column second line", paras[1].text)
    }

    @Test
    fun centeredTextIsMerged() {
        val lines = listOf(
            TextLine("this sentence is centered and", 100, 100, 980, 140),
            TextLine("continues here", 330, 154, 750, 194),
        )
        val paras = ParagraphBuilder.build(lines)
        assertEquals(1, paras.size)
        assertEquals("this sentence is centered and continues here", paras[0].text)
    }

    @Test
    fun differentFontSizesAreNotMerged() {
        val lines = listOf(
            TextLine("Big title", 20, 100, 600, 180),
            TextLine("small body text that follows", 20, 200, 700, 236),
        )
        assertEquals(2, ParagraphBuilder.build(lines).size)
    }

    @Test
    fun bulletsStartNewParagraph() {
        val lines = listOf(
            TextLine("• first item of the list here", 20, 100, 700, 140),
            TextLine("• second item of the list", 20, 154, 650, 194),
        )
        assertEquals(2, ParagraphBuilder.build(lines).size)
    }

    @Test
    fun joinHandlesHyphenAndCjk() {
        assertEquals("translation works", ParagraphBuilder.joinLines(listOf("transla-", "tion works")))
        assertEquals("Jean-Pierre", ParagraphBuilder.joinLines(listOf("Jean-", "Pierre")))
        assertEquals("今天天气很好我们去公园", ParagraphBuilder.joinLines(listOf("今天天气很好", "我们去公园")))
        assertEquals("a b", ParagraphBuilder.joinLines(listOf(" a ", "b")))
    }

    @Test
    fun translatableText() {
        assertFalse(ParagraphBuilder.isTranslatable("Q2.983"))
        assertFalse(ParagraphBuilder.isTranslatable("6:23 • 40%"))
        assertTrue(ParagraphBuilder.isTranslatable("OK"))
        assertTrue(ParagraphBuilder.isTranslatable("好"))
    }

    @Test
    fun sentenceEndDetection() {
        assertTrue(ParagraphBuilder.endsSentence("génération. \"—"))
        assertTrue(ParagraphBuilder.endsSentence("Hello!”"))
        assertFalse(ParagraphBuilder.endsSentence("productions puissantes et"))
    }
}
