package com.dichmanhinh.screentranslator

import com.dichmanhinh.screentranslator.LangRouting.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LangRoutingTest {

    @Test
    fun mixedVietnameseChineseScreen() {
        // Màn hình video học tiếng Trung: chữ Hán phải dịch từ tiếng Trung, tiếng Việt bỏ qua
        assertEquals(Action.Translate("zh-CN"), LangRouting.decide("zh", "auto", "vi"))
        assertEquals(Action.SkipTarget, LangRouting.decide("vi", "auto", "vi"))
        assertEquals(Action.SkipRomanized, LangRouting.decide("zh-Latn", "auto", "vi"))
        assertEquals(Action.Translate("auto"), LangRouting.decide("und", "auto", "vi"))
    }

    @Test
    fun fixedSourceLanguage() {
        assertEquals(Action.Translate("fr"), LangRouting.decide("fr", "fr", "vi"))
        assertEquals(Action.Translate("fr"), LangRouting.decide("und", "fr", "vi"))
        // Nguồn đặt là tiếng Pháp nhưng đoạn chữ là tiếng Nga: dịch từ tiếng Nga
        assertEquals(Action.Translate("ru"), LangRouting.decide("ru", "fr", "vi"))
        assertEquals(Action.SkipTarget, LangRouting.decide("vi", "fr", "vi"))
    }

    @Test
    fun codeMapping() {
        assertEquals(Action.Translate("iw"), LangRouting.decide("he", "auto", "vi"))
        assertEquals(Action.Translate("jw"), LangRouting.decide("jv", "auto", "vi"))
        assertEquals(Action.SkipTarget, LangRouting.decide("zh", "auto", "zh-TW"))
        assertEquals(Action.Translate("auto"), LangRouting.decide("xx", "auto", "vi"))
        assertEquals("ja", LangRouting.scriptLanguage("日本語を勉強します"))
        assertEquals("ko", LangRouting.scriptLanguage("안녕하세요"))
        assertEquals("zh", LangRouting.scriptLanguage("謝謝"))
        assertEquals(null, LangRouting.scriptLanguage("Xin chào"))
    }
}
