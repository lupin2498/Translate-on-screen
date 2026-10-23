package com.dichmanhinh.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPrepTest {

    @Test
    fun spacesBetweenHanAreRemoved() {
        // "不 是" bị Google dịch thành "không, nó đấy"; "不是" mới là "không phải"
        assertEquals("不是", TextPrep.clean("不 是"))
        assertEquals("1。好的", TextPrep.clean("1。 好的"))
        assertEquals("ありがとうございます", TextPrep.clean("ありがとう ございます"))
        assertEquals("好的 OK", TextPrep.clean("好的 OK"))
        // Tiếng Hàn có dấu cách giữa các từ: giữ nguyên
        assertEquals("안녕 하세요", TextPrep.clean("안녕 하세요"))
    }

    @Test
    fun mixedScriptLookalikesAreFixed() {
        // ML Kit đọc vài chữ Kirin thành chữ La-tinh giống hệt (p, e, a): máy dịch không nhận ra từ
        assertEquals("Привет, как дела?", TextPrep.clean("Пpивeт, кaк дeлa?"))
        assertEquals("СЕГОДНЯ", TextPrep.fixMixedScript("СЕГOДНЯ"))
        // Chữ Kirin lẫn vào từ La-tinh
        assertEquals("Hello world", TextPrep.fixMixedScript("Hеllo wоrld"))
        // Từ thuần một bộ chữ, hoặc chữ lẫn không có cặp giống hệt: giữ nguyên
        assertEquals("Привет Mike, iPhone 15", TextPrep.fixMixedScript("Привет Mike, iPhone 15"))
        assertEquals("Пrивет", TextPrep.fixMixedScript("Пrивет"))
        assertEquals("Москва", TextPrep.fixMixedScript("Москва"))
    }

    @Test
    fun russianVideoNoiseIsRemoved() {
        // Chữ Tesseract đọc từ video tiếng Nga (viền chữ, khuôn mặt bị đọc thành ký tự)
        assertEquals(
            "ОНА ПОЗВОНИЛА СОПЕРНИЦЕ ПРЯМО ПЕРЕД СВАДЬБОЙ —И",
            TextPrep.clean("ОНА\\` ПОЗВОНИЛА СОПЕРНИЦЕ ПРЯМО).ПЕРЕД СВАДЬБОЙ —И")
        )
        assertEquals("Жанна Болотова, дружившая", TextPrep.clean("Жанна © Болотова, дружившая"))
        assertEquals("позвонила сопернице и произнесла", TextPrep.clean("позвонила сопернице и//)1 произнесла"))
        assertEquals("слишком поздно.", TextPrep.clean("слишком поздно)."))
    }

    @Test
    fun legitimateTextIsKept() {
        val keep = listOf(
            "Xin chào (tiếng Việt)",
            "1) Mở ứng dụng 2) Chọn ngôn ngữ",
            "a) b) c)",
            "Cảm ơn :)",
            "https://example.com/a/b",
            "km/h và 50/50",
            "© 2024 Google",
            "C:\\Users\\Public",
            "TCP/IP and/or UDP",
            "Hello | World",
            "Brand™ Name",
            "[OK] {x}",
            "MP3 A4 H2O",
        )
        for (s in keep) assertEquals(s, TextPrep.clean(s))
    }

    @Test
    fun allCapsDetection() {
        assertTrue(TextPrep.isAllCaps("ОНА ПОЗВОНИЛА СОПЕРНИЦЕ ПРЯМО ПЕРЕД СВАДЬБОЙ"))
        assertTrue(TextPrep.isAllCaps("BREAKING NEWS TODAY"))
        assertFalse(TextPrep.isAllCaps("Breaking news today"))
        assertFalse(TextPrep.isAllCaps("NASA")) // một từ: có thể là tên viết tắt
        assertFalse(TextPrep.isAllCaps("OK GO")) // quá ít chữ
        assertFalse(TextPrep.isAllCaps("好的 不是"))
        assertFalse(TextPrep.isAllCaps("The USA and the UK are allies"))
    }

    @Test
    fun sentenceCase() {
        assertEquals(
            "Она позвонила сопернице прямо перед свадьбой — и",
            TextPrep.toSentenceCase("ОНА ПОЗВОНИЛА СОПЕРНИЦЕ ПРЯМО ПЕРЕД СВАДЬБОЙ — И")
        )
        assertEquals("Big sale. Buy now! Only 3.5 kg", TextPrep.toSentenceCase("BIG SALE. BUY NOW! ONLY 3.5 KG"))
    }
}
