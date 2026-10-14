package com.dichmanhinh.screentranslator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityTest {

    /** Chữ Nga trong video (ảnh người dùng gửi) bị ML Kit đọc thành chữ La-tinh. */
    private val misread = listOf(
        "HKONan 「y6eHKO roTOBAncA KeHMTbCA Ha",
        "aKTpMce MapuaHHe Bep™HCKOÄ. 3aaBneHHe nogan,",
        "CBNAeTeneM AonKeH 6biN CTaTb caM BnaquMMp",
        "BbicOuKMÄ. Ho 3a HecKONbKO ahem Ao cBanb6bl",
        "pa3Aanc月 3BOHOK. KaHHa 5onoroBa, ApyKMBEa月 C",
        "「y6eHKOe三é co CTYAeH4eCKnX net, no3BOHMna",
        "conepHMLe 20⊐pon3Hecna oAHy ф pa3y: KaK oH",
        "MOKET KeHMTbCA, ecnMy no6OB Ha? CBaAb6a",
        "copBanaCb. TaK HayancA pOMaH, KOTopblm⊐",
        "poAnMncR ⊐9TbAeCRT CeMbneT. A BeAb myTb5",
        "「y6eHKO Ha9MHanc月 B oneccKMX KaTaKOM6ax,",
        "「Ae OH noTepa MaTb. AeTAOMOBe, MeyTaBwMÄ 06",
        "aKTëpCTBe, cran nocneAHMM MMHVCTpOM KynbTypbi",
        "CCCP. Lưu⊐ocTABbTE /\\AiK, ecnn Bbl cyMraeTe, yTO",
        "HacTOAuyio nio60Bb CTOMT OTCTaMBaTb, Aaxe KorAa",
        "KaxeTCA, 4TO yKe cnMUKOM NO3AHO.",
        "OHA NO3BOHNNA COnEPHNUE",
        "NPRMO NEPED CBAAbBOЙ - N",
        "XN3Hb N3MEHNNACb HA 57 NET",
    )

    private val normal = listOf(
        "Kênh đăng ký", "Trang chủ", "Shorts", "Đăng ký", "Chia sẻ", "Lưu",
        "YouTube TikTok iPhone McDonald",
        "En mélangeant une drill sombre, des productions puissantes et",
        "Thank you very much", "The quick brown fox jumps over the lazy dog.",
        "URLs IDs GHz PDFs macOS iOS OpenAI ChatGPT LLaMA",
        "Situation en 2026 : Au milieu de l'année", "COVID-19 4G mp3 H2O", "MP3s CDs DVDs",
        "hảo tơ", "pú tuây", "Không phải", "SoundCloud PlayStation DeepMind",
        "Tìm kiếm \"tu vung tieng trung\"", "23 tháng 9 • Xem bản dịch",
        "LeBron DiCaprio MacBook LinkedIn WiFi PowerPoint",
        "GitHub JavaScript iPadOS OnePlus eSIM kWh mAh BMWs McLaren MySQL",
        "PHỞ BÒ TÁI NẠM", "Nhà hàng PHỞ 24 mở cửa 6h-22h", "iPhone 15 Pro Max 256GB", "B2B B2C SaaS",
    )

    @Test
    fun detectsCyrillicMisreadAsLatin() {
        for (line in misread) assertTrue("missed: $line", OcrQuality.isMisreadLine(line))
    }

    @Test
    fun doesNotFlagNormalText() {
        for (line in normal) assertFalse("false positive: $line", OcrQuality.isMisreadLine(line))
    }

    @Test
    fun scriptRatios() {
        assertTrue(OcrQuality.cyrillicRatio("Николай Губенко готовился") > 0.99f)
        assertTrue(OcrQuality.cyrillicRatio("HKONan y6eHKO") == 0f)
        assertTrue(OcrQuality.nonLatinRatio("مرحبا بالعالم") > 0.99f)
        assertTrue(OcrQuality.nonLatinRatio("Xin chào") == 0f)
    }
}
