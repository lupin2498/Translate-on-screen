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

    /**
     * Bảng từ vựng trong video (ảnh người dùng gửi): mỗi hàng "1 。好的 | hảo tơ | Vâng !" và pinyin
     * nhỏ phía trên chữ Hán. Mỗi ô phải là một đoạn riêng, pinyin bị bỏ qua.
     */
    @Test
    fun vocabularyTableRowsAreNotMerged() {
        val rows = listOf(
            Triple("好的", "hảo tơ", "Vâng !"), Triple("好吧", "hảo ba", "Được thôi !"),
            Triple("不是", "pú sư", "Không phải"), Triple("不對", "pú tuây", "Không đúng"),
            Triple("沒有", "mấy dẩu", "Không có"), Triple("可以", "khửa i", "Có thể"),
        )
        val pinyin = listOf("hǎo de", "hǎo ba", "bù shì", "bù duì", "méi yǒu", "kě yǐ")
        val lines = ArrayList<TextLine>()
        lines.add(TextLine("Từ cơ bản hay dùng", 240, 245, 655, 295))
        rows.forEachIndexed { i, (han, phon, mean) ->
            val y = 385 + i * 103
            lines.add(TextLine("${i + 1} 。", 22, y, 120, y + 55))
            lines.add(TextLine(pinyin[i], 168, y - 28, 298, y - 6))
            lines.add(TextLine(han, 165, y, 305, y + 58))
            lines.add(TextLine(phon, 360, y + 8, 530, y + 52))
            lines.add(TextLine(mean, 582, y + 2, 800, y + 54))
        }
        val texts = ParagraphBuilder.build(lines).map { it.text }
        for ((han, phon, mean) in rows) {
            assertTrue("$han must be its own unit: $texts", texts.contains(han))
            assertTrue("$phon must be its own unit: $texts", texts.contains(phon))
            assertTrue("$mean must be its own unit: $texts", texts.contains(mean))
        }
        for (p in pinyin) assertFalse("pinyin '$p' must be skipped: $texts", texts.any { it.contains(p) })
        assertTrue(texts.contains("Từ cơ bản hay dùng"))
    }

    /** Đoạn văn dài có nút bấm ngang hàng ("Lưu", "720") vẫn phải được ghép trọn đoạn. */
    @Test
    fun paragraphNextToButtonsStillMerges() {
        val lines = listOf(
            TextLine("Но за несколько дней до свадьбы раздался", 90, 960, 790, 995),
            TextLine("звонок. Жанна Болотова, дружившая с", 120, 1023, 760, 1058),
            TextLine("720", 810, 1065, 870, 1100), // nút "thích" ngang hàng dòng 3
            TextLine("студенческих лет, позвонила сопернице и", 100, 1060, 780, 1095),
            TextLine("Lưu", 795, 1360, 850, 1395),
        )
        val paras = ParagraphBuilder.build(lines)
        val main = paras.first { it.text.startsWith("Но за") }
        assertEquals(paras.joinToString("\n") { it.text }, 3, main.lines)
        assertTrue(paras.any { it.text == "720" })
        assertTrue(paras.any { it.text == "Lưu" })
    }

    /** Khung chữ căn giữa trong video tiếng Nga: dòng dài ngắn khác nhau nhưng là một đoạn. */
    @Test
    fun centeredParagraphKeepsNamesTogether() {
        val texts = listOf(
            "Николай Губенко готовился жениться на" to 760,
            "актрисе Марианне Вертинской. Заявление" to 800,
            "подали, свидетелем должен был стать сам" to 780,
            "Владимир Высоцкий. Но за несколько дней" to 890,
            "до свадьбы раздался звонок. Жанна" to 690,
            "Болотова, дружившая с Губенко ещё со" to 760,
            "студенческих лет, позвонила сопернице и" to 820,
        )
        val lines = texts.mapIndexed { i, (t, w) ->
            val left = 540 - w / 2
            TextLine(t, left, 300 + i * 46, left + w, 334 + i * 46)
        }
        val paras = ParagraphBuilder.build(lines)
        assertEquals(paras.joinToString("\n") { it.text }, 1, paras.size)
        assertTrue(paras[0].text.contains("звонок. Жанна Болотова, дружившая"))
    }

    /** Bộ chữ Trung gộp 2 hàng bảng thành một dòng: dựng lại từ từng từ, tách hàng và tách ô. */
    @Test
    fun groupRowsSplitsMergedTableRows() {
        val els = listOf(
            TextLine("4", 22, 700, 60, 770), TextLine("。", 100, 740, 125, 765),
            TextLine("不對", 198, 700, 366, 770),
            TextLine("pú", 445, 712, 520, 760), TextLine("tuây", 535, 712, 655, 760),
            TextLine("5", 22, 823, 60, 893), TextLine("。", 100, 863, 125, 888),
            TextLine("沒有", 198, 823, 366, 893),
            TextLine("mấy", 420, 835, 520, 883), TextLine("dẩu", 535, 835, 650, 883),
        )
        val rows = ParagraphBuilder.groupRows(els).map { it.text }
        for (x in listOf("不對", "沒有", "pú tuây", "mấy dẩu")) assertTrue("$x: $rows", rows.contains(x))
        assertTrue(rows.toString(), rows.none { it.contains("不對") && it.contains("沒有") })
    }

    @Test
    fun groupRowsKeepsSentenceTogether() {
        val words = "The quick brown fox jumps".split(" ")
        var x = 20
        val els = words.map { w ->
            val e = TextLine(w, x, 100, x + w.length * 22, 140); x = e.right + 12; e
        }
        assertEquals(listOf("The quick brown fox jumps"), ParagraphBuilder.groupRows(els).map { it.text })
        // Chữ Trung trộn chữ La-tinh trong cùng câu (khoảng cách nhỏ) vẫn là một dòng
        val mixed = listOf(TextLine("我喜欢", 20, 100, 200, 160), TextLine("iPhone", 212, 105, 330, 155))
        assertEquals(listOf("我喜欢iPhone"), ParagraphBuilder.groupRows(mixed).map { it.text })
    }

    /** Bộ chữ Trung dính chữ Hán với phiên âm bên cạnh: tách theo bộ chữ, ước lượng vị trí. */
    @Test
    fun splitByScriptSeparatesHanAndPhonetic() {
        val pieces = ParagraphBuilder.splitByScript(TextLine("1。好的hao to", 22, 400, 655, 470))
        assertEquals(listOf("1。好的", "hao to"), pieces.map { it.text })
        assertTrue(pieces[0].right <= pieces[1].left)
        assertTrue("Han piece should end near x=366: ${pieces[0]}", pieces[0].right in 330..400)
        val merged = ParagraphBuilder.splitByScript(TextLine("5。沒有my diu Khóngc6可以khrai Cóthé", 22, 800, 960, 990))
        assertEquals(listOf("5。沒有", "my diu Khóngc6", "可以", "khrai Cóthé"), merged.map { it.text })
        assertEquals(listOf("Xin chào"), ParagraphBuilder.splitByScript(TextLine("Xin chào", 0, 0, 100, 20)).map { it.text })
    }

    @Test
    fun rubyElementsAreRemoved() {
        val han = listOf(TextLine("好的", 198, 460, 366, 530))
        val els = listOf(
            TextLine("hǎo", 200, 430, 250, 456), TextLine("de", 310, 430, 350, 456), // pinyin
            TextLine("hảo tơ", 445, 472, 655, 520), // phiên âm Việt cùng hàng: giữ
        )
        assertEquals(listOf("hảo tơ"), ParagraphBuilder.removeRuby(els, han).map { it.text })
    }

    @Test
    fun furiganaIsSkipped() {
        val lines = listOf(
            TextLine("にほんご", 100, 70, 260, 92),
            TextLine("日本語を勉強します", 90, 100, 520, 160),
        )
        assertEquals(listOf("日本語を勉強します"), ParagraphBuilder.build(lines).map { it.text })
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
        assertFalse(ParagraphBuilder.isTranslatable("4 。"))
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
