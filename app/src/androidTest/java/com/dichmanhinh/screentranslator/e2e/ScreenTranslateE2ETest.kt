package com.dichmanhinh.screentranslator.e2e

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.dichmanhinh.screentranslator.DebugInfo
import com.dichmanhinh.screentranslator.GoogleTranslator
import com.dichmanhinh.screentranslator.OcrQuality
import com.dichmanhinh.screentranslator.OverlayStyle
import com.dichmanhinh.screentranslator.Prefs
import com.dichmanhinh.screentranslator.R
import com.dichmanhinh.screentranslator.ScreenTranslateService
import com.dichmanhinh.screentranslator.SettingsActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * End-to-end: sample "other app" with English text -> MainActivity "Start" -> SystemUI MediaProjection
 * consent (entire screen / a single app + app selector) -> floating bubble tap -> OCR -> Google
 * Translate -> overlay. Verifies through the in-process DebugInfo hook (instrumentation runs in the
 * app process, so it shares the service's statics) and through accessibility window inspection.
 *
 * Run one mode per `am instrument` so each starts from a fresh app process:
 *   am instrument -w -r -e class com.dichmanhinh.screentranslator.e2e.ScreenTranslateE2ETest#singleApp \
 *     com.dichmanhinh.screentranslator.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreenTranslateE2ETest {

    private enum class Mode(val label: String) { ENTIRE_SCREEN("entire-screen"), SINGLE_APP("single-app") }

    companion object {
        private const val TAG = "ST-E2E"
        private const val PKG = "com.dichmanhinh.screentranslator"
        private const val TEST_PKG = "com.dichmanhinh.screentranslator.test"
        private const val SAMPLE = "$TEST_PKG/com.dichmanhinh.screentranslator.e2e.SampleTextActivity"
        private const val SAMPLE_LABEL = "ST E2E Sample"
        private const val MAIN = "$PKG/.MainActivity"
        private const val SYSUI = "com.android.systemui"
        private const val SHOT_DIR = "/data/local/tmp/st-e2e"

        /** Must match SampleTextActivity.LINES. */
        private val SAMPLE_LINES = listOf(
            "Hello world",
            "Good morning",
            "The weather is beautiful today",
            "Please open the door",
            "Thank you very much",
        )

        private val VI_DIACRITICS = Regex(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]",
            RegexOption.IGNORE_CASE
        )

        // ---- SystemUI selectors (AOSP frameworks/base/packages/SystemUI) ----
        // Factories, not vals: BySelector builder methods (hasDescendant, text, ...) MUTATE and return `this`.
        // Android 14 QPR2+/15: screen_share_mode_spinner; Android 16: screen_share_mode_options.
        private fun spinnerSel(): BySelector =
            By.res(Pattern.compile("$SYSUI:id/screen_share_mode_(spinner|options)"))
        // Positive button: "Start" (14/15), "Next"/"Share screen" (16), legacy AlertDialog "Start now".
        private fun positiveSel(): BySelector = By.pkg(SYSUI).res("android:id/button1")
        private fun negativeSel(): BySelector = By.pkg(SYSUI).res(Pattern.compile("android:id/button[23]"))
        // By.text(Pattern) is a FULL match.
        private val SINGLE_APP_TEXT: Pattern =
            Pattern.compile("(A single app|Share one app|Single app|One app)", Pattern.CASE_INSENSITIVE)
        private val ENTIRE_TEXT: Pattern =
            Pattern.compile("(Entire screen|Share entire screen|Full screen)", Pattern.CASE_INSENSITIVE)
        private fun recentsSel(): BySelector = By.res("$SYSUI:id/media_projection_recent_tasks_recycler")
    }

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instr)
    private val ctx get() = instr.targetContext
    private val appLabel by lazy { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
    private var mode: Mode? = null
    private var step = 0

    // Rules wrap @Before/@After, so failed() runs AFTER tearDown(); state capture lives in tearDown().
    @get:Rule
    val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            report("FAIL ${description.methodName}: ${e.javaClass.simpleName}: ${e.message}")
        }

        override fun skipped(e: org.junit.AssumptionViolatedException, description: Description) {
            report("SKIP ${description.methodName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ setup

    @Before
    fun setUp() {
        shell("mkdir -p $SHOT_DIR")
        report("device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} fp=${Build.FINGERPRINT}")
        device.wakeUp()
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")

        // 1. Permissions: overlay is an app-op (pm grant does not work for it), notifications is runtime.
        shell("appops set $PKG SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $PKG ${Manifest.permission.POST_NOTIFICATIONS}")
        // PROJECT_MEDIA=allow would make SystemUI skip the consent dialog and auto-grant entire screen.
        shell("appops set $PKG PROJECT_MEDIA default")
        assertTrue("SYSTEM_ALERT_WINDOW not granted", waitUntil(5_000) { Settings.canDrawOverlays(ctx) })

        // 2. Deterministic languages: English -> Vietnamese (in-process SharedPreferences).
        Prefs.save(ctx, "en", "vi")

        // 3. Clean state.
        stopServiceIfRunning()
        clickIfPresent(By.res("android:id/aerr_wait"), "ANR 'Wait'")
    }

    @After
    fun tearDown() {
        dumpState("tearDown")
        shot("final")
        dumpHierarchy("final") // a11y tree of all windows (bubble, overlay, SystemUI) at the end of the test
        stopServiceIfRunning()
        device.pressHome()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun entireScreen() = runE2E(Mode.ENTIRE_SCREEN)

    @Test
    fun singleApp() = runE2E(Mode.SINGLE_APP)

    /** Bật "Luôn chia sẻ toàn bộ màn hình": hộp thoại phải mặc định chọn toàn màn hình. */
    @Test
    fun entireScreenForced() {
        Prefs.setForceFullScreen(ctx, true)
        try {
            runE2E(Mode.ENTIRE_SCREEN)
        } finally {
            Prefs.setForceFullScreen(ctx, false)
        }
    }

    /** Kiểu chữ tùy chỉnh: nền trong suốt, viền chữ, cỡ cố định, font serif, đậm, màu vàng. */
    @Test
    fun styledOverlay() {
        OverlayStyle.save(
            ctx,
            OverlayStyle(
                transparentBg = true, outline = true, autoSize = false, textSizeSp = 22,
                font = "serif", bold = true, textColor = Color.YELLOW, border = false
            )
        )
        try {
            runE2E(Mode.ENTIRE_SCREEN)
        } finally {
            OverlayStyle.save(ctx, OverlayStyle())
        }
    }

    /** Nhấn giữ nút nổi -> kéo chọn vùng chứa 2 dòng giữa -> chỉ 2 dòng đó được dịch. */
    @Test
    fun regionSelect() {
        startSession(Mode.ENTIRE_SCREEN)
        val a = device.findObject(By.pkg(TEST_PKG).text(SAMPLE_LINES[1]))?.visibleBounds
        val b = device.findObject(By.pkg(TEST_PKG).text(SAMPLE_LINES[2]))?.visibleBounds
        assertTrue("sample lines not found", a != null && b != null)
        val r = Rect(a!!).apply { union(b!!); inset(0, -8) }
        report("region to select: $r")
        tapAndVerify(
            Mode.ENTIRE_SCREEN,
            expected = listOf(SAMPLE_LINES[1], SAMPLE_LINES[2]),
            minMatched = 2,
            excluded = listOf(SAMPLE_LINES[0], SAMPLE_LINES[4]),
            retryTap = false,
        ) { bubble ->
            val bb = bubble ?: findBubble()
            assertTrue("bubble not found", bb != null)
            bb!!.longClick()
            val shown = device.wait(Until.hasObject(By.res(PKG, "regionSelect")), 5_000) == true
            report("region selector shown=$shown")
            shot("region-selector")
            assertTrue("region selector overlay not shown after long-press", shown)
            device.drag(r.left, r.top, r.right, r.bottom, 40)
        }
    }

    /** Chia sẻ một ứng dụng rồi về màn hình chính: phải báo nội dung bị ẩn, không crash; quay lại thì dịch được. */
    @Test
    fun singleAppHidden() {
        startSession(Mode.SINGLE_APP)
        device.pressHome()
        SystemClock.sleep(2_000)
        shot("home")
        val calls = DebugInfo.translateCalls
        tapBubble(findBubble())
        val hidden = waitUntil(5_000) { DebugInfo.lastError == "content hidden" }
        report("hidden detected=$hidden lastError=${DebugInfo.lastError} calls=${DebugInfo.translateCalls - calls}")
        assertTrue("expected 'content hidden' when the shared app is not visible", hidden)
        assertTrue("service must keep running", ScreenTranslateService.isRunning)

        shell("am start -W -n $SAMPLE")
        assertTrue("sample not back in foreground", waitForSample(10_000))
        SystemClock.sleep(1_500)
        DebugInfo.lastError = null
        tapAndVerify(Mode.SINGLE_APP)
    }

    /**
     * Đoạn văn tiếng Pháp tự xuống dòng giữa câu (giống chú thích Instagram người dùng gửi).
     * Các dòng phải được ghép thành đoạn trước khi dịch để giữ ngữ cảnh:
     * "réseaux / sociaux" phải ra "mạng xã hội", "Ses morceaux" không được ra "quân cờ".
     */
    @Test
    fun paragraphCoherence() {
        Prefs.save(ctx, "auto", "vi")
        startSession(Mode.ENTIRE_SCREEN, sampleArgs = "--es mode paragraph", kind = "paragraph")
        tapAndVerify(Mode.ENTIRE_SCREEN, expected = listOf("Gazo"), minMatched = 1)
        val all = DebugInfo.lastTranslations.joinToString(" ").lowercase()
        report("paragraph units=${DebugInfo.lastBlocks}")
        assertTrue(
            "paragraph lines should be merged into a few units, got ${DebugInfo.lastBlocks}",
            DebugInfo.lastBlocks in 1..4
        )
        assertTrue("'réseaux sociaux' must be translated as one phrase: $all", all.contains("mạng xã hội"))
        assertFalse("context lost ('morceaux' -> 'quân cờ'): $all", all.contains("quân cờ"))
        report("PASS paragraphCoherence")
    }

    /**
     * Ảnh thật người dùng gửi: video học tiếng Trung (bảng từ vựng chữ Hán + pinyin + phiên âm Việt +
     * nghĩa tiếng Việt). Lỗi cũ: cả lô bị Google nhận là tiếng Việt -> "không có gì cần dịch".
     */
    @Test
    fun videoTableChinese() {
        Prefs.save(ctx, "auto", "vi")
        startSession(Mode.ENTIRE_SCREEN, sampleArgs = "--es mode image --es asset video_table_zh.jpg", kind = "image")
        tapAndVerify(Mode.ENTIRE_SCREEN, expected = listOf("好的", "好吧", "不是", "謝謝", "可以"), minMatched = 3)
        reportUnits()
        val vocab = listOf("好的", "好吧", "不是", "不對", "沒有", "可以", "謝謝", "不要", "不想", "真的", "不用", "不行")
        val units = DebugInfo.lastUnits
        val han = units.filter { u -> u.original.any { it.code in 0x4E00..0x9FFF } }
        val translatedHan = han.filter { it.action.startsWith("translate") && !it.translated.isNullOrBlank() }
        report("han units=${han.size} translated=${translatedHan.size}")
        assertTrue("most Chinese cells must be translated: $han", translatedHan.size >= 8)
        val merged = han.filter { u -> vocab.count { u.original.contains(it) } >= 2 }
        assertTrue("table rows must not be merged: $merged", merged.isEmpty())
        val thanks = units.firstOrNull { it.original.contains("謝謝") }
        assertTrue("謝謝 must be translated as 'cảm ơn': $thanks",
            thanks?.translated?.lowercase()?.contains("cảm ơn") == true)
        assertTrue("Vietnamese text must be skipped, not re-translated",
            units.any { it.action == "skip-target" })
        report("PASS videoTableChinese")
    }

    /**
     * Ảnh thật người dùng gửi: video tiếng Nga. ML Kit không đọc được chữ Kirin (ra chữ rác
     * "HKONan y6eHKO") -> phải đọc lại bằng Tesseract và dịch từ tiếng Nga.
     */
    @Test
    fun videoRussianCyrillic() {
        Prefs.save(ctx, "auto", "vi")
        startSession(Mode.ENTIRE_SCREEN, sampleArgs = "--es mode image --es asset video_text_ru.jpg", kind = "image")
        tapAndVerify(Mode.ENTIRE_SCREEN, expected = listOf("Губенко", "Высоцкий", "свадьбы"), minMatched = 1)
        reportUnits()
        assertTrue("Tesseract (rus) must be used: ${DebugInfo.lastOcr}", DebugInfo.lastOcr?.contains("rus") == true)
        val units = DebugInfo.lastUnits
        val garbage = units.filter { OcrQuality.isMisreadLine(it.original) }
        assertTrue("no garbage text may be translated: $garbage", garbage.isEmpty())
        val ru = units.filter { OcrQuality.cyrillicRatio(it.original) >= 0.6f && it.action.startsWith("translate") }
        assertTrue("Russian text must be translated: $units", ru.isNotEmpty())
        val all = DebugInfo.lastTranslations.joinToString(" ").lowercase()
        assertTrue("translation should mention Gubenko/Vysotsky: $all",
            all.contains("gubenko") || all.contains("vysotsky") || all.contains("vysotskiy"))
        report("PASS videoRussianCyrillic")
    }

    /** Màn hình Cài đặt hiển thị: thay đổi từng tùy chọn, kiểm tra được lưu, khôi phục mặc định. */
    @Test
    fun settingsScreen() {
        OverlayStyle.save(ctx, OverlayStyle())
        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val sw = device.wait(Until.findObject(By.res(PKG, "swTransparent")), 10_000)
        assertTrue("settings screen not shown", sw != null)
        shot("settings-open")

        sw!!.click()
        assertTrue("transparent not saved", waitUntil(3_000) { OverlayStyle.load(ctx).transparentBg })

        val color = OverlayStyle.COLORS[6]
        val desc = ctx.getString(R.string.color_desc, String.format("#%06X", color and 0xFFFFFF))
        val chips = device.findObjects(By.pkg(PKG).desc(desc))
        report("color chips '$desc' found=${chips.size}")
        assertTrue("color chips not found", chips.size >= 2)
        chips.last().click()
        assertTrue("text color not saved", waitUntil(3_000) { OverlayStyle.load(ctx).textColor == color })

        scrollTo(By.res(PKG, "swAutoSize"))!!.click()
        assertTrue("autoSize off not saved", waitUntil(3_000) { !OverlayStyle.load(ctx).autoSize })

        val slider = scrollTo(By.res(PKG, "sliderSize"))
        assertTrue("size slider not found", slider != null)
        val sb = slider!!.visibleBounds
        device.drag(sb.left + sb.width() / 4, sb.centerY(), sb.right - 4, sb.centerY(), 20)
        assertTrue("text size not saved", waitUntil(3_000) { OverlayStyle.load(ctx).textSizeSp > 30 })

        scrollTo(By.res(PKG, "spinnerFont"))!!.click()
        val serif = OverlayStyle.FONTS.first { it.key == "serif" }.label
        device.wait(Until.findObject(By.text(serif)), 5_000)!!.click()
        assertTrue("font not saved", waitUntil(3_000) { OverlayStyle.load(ctx).font == "serif" })

        scrollTo(By.res(PKG, "swBold"))!!.click()
        scrollTo(By.res(PKG, "swOutline"))!!.click()
        assertTrue("bold/outline not saved", waitUntil(3_000) {
            OverlayStyle.load(ctx).let { it.bold && it.outline }
        })
        val saved = OverlayStyle.load(ctx)
        report("saved style: $saved")
        shot("settings-changed")

        scrollTo(By.res(PKG, "btnReset"))!!.click()
        assertTrue("reset failed", waitUntil(3_000) { OverlayStyle.load(ctx) == OverlayStyle() })
        shot("settings-reset")
        report("PASS settingsScreen")
        device.pressBack()
    }

    // ------------------------------------------------------------------ flow

    private fun runE2E(m: Mode) {
        startSession(m)
        tapAndVerify(m)
    }

    /** Mở app mẫu, bấm Bắt đầu, xử lý hộp thoại cấp quyền; trả về khi nút nổi đã sẵn sàng. */
    private fun startSession(m: Mode, sampleArgs: String = "", kind: String = "lines") {
        sampleKind = kind
        mode = m
        report("==== START mode=${m.label} sdk=${Build.VERSION.SDK_INT}")

        // A. Network pre-check: separates "emulator offline" from "capture broken".
        val probe = runCatching {
            runBlocking { withTimeout(30_000) { GoogleTranslator.translate("Good morning", "en", "vi") } }
        }
        report("translate pre-check: ${probe.getOrElse { "ERROR $it" }}")
        assertTrue("Emulator cannot reach translate.googleapis.com: ${probe.exceptionOrNull()}", probe.isSuccess)

        // B. "Other app" with known English text, in its own package/process/task.
        val tick = InstrumentationRegistry.getArguments().getString("sampleTick") == "true"
        shell("am start -W -n $SAMPLE --ez tick $tick $sampleArgs")
        assertTrue("sample app not shown", waitForSample(15_000))
        shot("sample")

        // C. Translator app -> Start.
        shell("am start -W -n $MAIN")
        val start = device.wait(Until.findObject(By.res(PKG, "btnStart")), 15_000)
        assertTrue("btnStart not found", start != null)
        shot("main")
        start!!.click()

        // D. Consent dialog (+ app selector for single app).
        clickIfPresent(By.res("com.android.permissioncontroller:id/permission_allow_button"), "notification permission")
        handleConsentDialog(m)
        if (m == Mode.SINGLE_APP) pickSampleInAppSelector()

        // E. Service must start even though MainActivity is STOPPED in single-app mode
        //    (SystemUI delivers the result with setForceSendResultForMediaProjection()).
        val running = waitUntil(20_000) { ScreenTranslateService.isRunning }
        report("service running=$running lastError=${DebugInfo.lastError}")
        assertTrue(
            "Consent given but ScreenTranslateService not running (mode=${m.label}). In single-app mode this is " +
                "the 'activity result held until MainActivity is STARTED again' bug. lastError=${DebugInfo.lastError}",
            running
        )

        if (m == Mode.ENTIRE_SCREEN) {
            // MainActivity.moveTaskToBack() should already reveal the sample; make it explicit.
            // (Never do this in single-app mode: a different task in front = captured content hidden.)
            shell("am start -W -n $SAMPLE --ez tick $tick $sampleArgs")
        }
        assertTrue("sample app not in foreground after consent", waitForSample(10_000))
        SystemClock.sleep(1_500) // let the VirtualDisplay deliver its first frame
    }

    /**
     * Kích hoạt dịch (mặc định: chạm nút nổi) rồi kiểm tra kết quả.
     * @param expected các câu mẫu phải được OCR đọc ra, ít nhất [minMatched] câu.
     * @param excluded các câu mẫu KHÔNG được xuất hiện (kiểm tra chọn vùng).
     */
    private fun tapAndVerify(
        m: Mode,
        expected: List<String> = SAMPLE_LINES,
        minMatched: Int = 2,
        excluded: List<String> = emptyList(),
        retryTap: Boolean = true,
        trigger: (UiObject2?) -> Unit = { tapBubble(it) },
    ) {
        val resultsBefore = DebugInfo.resultCount
        val callsBefore = DebugInfo.translateCalls

        // F. Tap the floating bubble.
        val bubble = findBubble()
        report("bubble found via a11y=${bubble != null} bounds=${bubble?.visibleBounds}")
        shot("before-tap")
        trigger(bubble)
        if (retryTap && !waitUntil(5_000) { DebugInfo.translateCalls > callsBefore }) {
            report("bubble tap not registered (translateCalls unchanged) -> retry by coordinates")
            tapBubble(null)
        }

        // G. Wait for result (or a reported failure).
        val done = waitUntil(90_000) {
            DebugInfo.resultCount > resultsBefore ||
                (DebugInfo.translateCalls > callsBefore && DebugInfo.lastError != null)
        }
        SystemClock.sleep(1_000) // overlay is added right after resultCount++
        shot("result")
        val originals = DebugInfo.lastOriginals
        val translations = DebugInfo.lastTranslations
        report(
            "RESULT mode=${m.label} sdk=${Build.VERSION.SDK_INT} done=$done results=${DebugInfo.resultCount} " +
                "calls=${DebugInfo.translateCalls} blocks=${DebugInfo.lastBlocks} lastError=${DebugInfo.lastError}"
        )
        originals.zip(translations).forEachIndexed { i, (o, t) ->
            report("  block[$i] '${o.replace('\n', ' ')}' -> '${t.replace('\n', ' ')}'")
        }

        assertTrue(
            "No translation result within 90s (mode=${m.label}). lastError=${DebugInfo.lastError} " +
                "calls=${DebugInfo.translateCalls - callsBefore}",
            DebugInfo.resultCount > resultsBefore
        )
        assertTrue("result has no blocks", DebugInfo.lastBlocks > 0)

        // OCR must have read the SAMPLE app (not MainActivity, launcher or a black frame).
        val seen = originals.joinToString(" ").lowercase().replace(Regex("\\s+"), " ")
        val matched = expected.count { seen.contains(it.lowercase()) }
        report("sample phrases recognized: $matched/${expected.size}")
        assertTrue("OCR did not read the sample app (matched=$matched): $originals", matched >= minMatched)
        val leaked = excluded.filter { seen.contains(it.lowercase()) }
        assertTrue("OCR read text outside the selected region: $leaked", leaked.isEmpty())

        val vi = translations.count { VI_DIACRITICS.containsMatchIn(it) }
        report("vietnamese-looking translations: $vi/${translations.size}")
        assertTrue("translations do not look Vietnamese: $translations", vi >= 1)

        // H. The result overlay window must be on screen.
        val overlay = verifyOverlayWindow()
        report("overlay window: $overlay")
        assertTrue("translation overlay window not found", overlay.startsWith("OK"))

        report("PASS mode=${m.label} sdk=${Build.VERSION.SDK_INT} blocks=${DebugInfo.lastBlocks} matched=$matched")
    }

    // ------------------------------------------------------------------ SystemUI automation

    private fun handleConsentDialog(m: Mode) {
        var spinner: UiObject2? = null
        var positive: UiObject2? = null
        val shown = waitUntil(20_000) {
            spinner = device.findObject(spinnerSel())
            positive = device.findObject(positiveSel())
            spinner != null || positive != null
        }
        shot("consent")
        assertTrue("MediaProjection consent dialog not shown", shown)
        val title = device.findObject(By.res("$SYSUI:id/screen_share_dialog_title"))?.text
            ?: device.findObject(By.pkg(SYSUI).res("android:id/alertTitle"))?.text
        report("consent dialog: spinner=${spinner != null} title='$title' positive='${positive?.text}'")

        if (spinner == null) {
            // Legacy dialog (Android <= 14 QPR1 images, or partial screen sharing disabled): entire screen only.
            if (m == Mode.SINGLE_APP) {
                device.findObject(negativeSel())?.click()
                report("SKIP single-app: legacy consent dialog without 'A single app' option on this image")
                assumeTrue("single-app sharing not offered by this system image", false)
            }
            (device.findObject(positiveSel()) ?: positive!!).click()
            return
        }

        selectSpinnerOption(if (m == Mode.SINGLE_APP) SINGLE_APP_TEXT else ENTIRE_TEXT)
        shot("consent-selected")
        val go = device.findObject(positiveSel())
        assertTrue("consent positive button not found", go != null)
        report("click consent button '${go!!.text}'")
        go.click()
    }

    private fun selectSpinnerOption(option: Pattern) {
        val spinner = device.findObject(spinnerSel()) ?: return
        if (spinner.hasObject(By.text(option))) {
            report("spinner already on '$option'")
            return
        }
        spinner.click()
        val item = device.wait(Until.findObject(By.pkg(SYSUI).text(option)), 5_000)
        assertTrue("spinner option $option not found", item != null)
        item!!.click()
        val ok = device.wait(Until.hasObject(spinnerSel().hasDescendant(By.text(option))), 3_000)
        report("spinner -> '$option' selected=$ok")
    }

    private fun pickSampleInAppSelector() {
        val recycler = device.wait(Until.findObject(recentsSel()), 20_000)
        assertTrue("app selector (recent tasks) not shown", recycler != null)
        SystemClock.sleep(1_000) // labels & thumbnails load asynchronously
        shot("app-selector")
        val descs = recycler!!.children.map { it.contentDescription }
        report("app selector tasks (${descs.size}): $descs  [selector title='${device.findObject(By.res("android:id/title"))?.text}']")

        val byLabel = Pattern.compile(
            "(?i).*(${Pattern.quote(SAMPLE_LABEL)}|${Pattern.quote(TEST_PKG)}).*"
        )
        // Each task item's contentDescription = application label of the task (RecentTaskViewHolder).
        var target: UiObject2? = device.wait(Until.findObject(By.pkg(SYSUI).desc(byLabel)), 5_000)
        if (target == null) {
            // Fallback: tasks of the requesting app are sorted LAST; the most recent other task comes first.
            target = device.findObject(recentsSel())?.children?.firstOrNull { c ->
                val d = c.contentDescription ?: ""
                d.isNotEmpty() && d != appLabel && d != PKG
            }
        }
        assertTrue("sample task not found in app selector: $descs", target != null)
        report("select task '${target!!.contentDescription}'")
        target.click()
    }

    // ------------------------------------------------------------------ bubble / overlay

    private fun findBubble(): UiObject2? =
        device.wait(Until.findObject(By.res(PKG, "bubbleIcon")), 15_000)
            ?: device.findObject(By.pkg(PKG).desc(appLabel))

    private fun tapBubble(bubble: UiObject2?) {
        if (bubble != null) {
            runCatching { bubble.click(); return }
        }
        // Fallback: default bubble position from ScreenTranslateService.showBubble() (x=0, y=35% height, 56dp).
        val dm = ctx.resources.displayMetrics
        val half = (28 * dm.density).toInt()
        val x = half
        val y = (dm.heightPixels * 0.35f).toInt() + half
        report("tap bubble by coordinates ($x,$y)")
        device.click(x, y)
    }

    /** Uses the optional R.id.translationOverlay hook when present, else inspects a11y nodes / dumpsys. */
    private fun verifyOverlayWindow(): String {
        val hookId = ctx.resources.getIdentifier("translationOverlay", "id", PKG)
        if (hookId != 0) {
            val o = device.wait(Until.findObject(By.res(PKG, "translationOverlay")), 10_000)
                ?: return "MISSING (hook id present but view not found)"
            return "OK via id; desc='${o.contentDescription?.take(200)}'"
        }
        // No hook: any node of our package (searched across ALL windows) that covers most of the screen
        // is the TYPE_APPLICATION_OVERLAY result window (the bubble window is only 56dp).
        val screenH = ctx.resources.displayMetrics.heightPixels
        val windows: List<Rect> = device.findObjects(By.pkg(PKG)).mapNotNull { runCatching { it.visibleBounds }.getOrNull() }
        val big = windows.filter { it.height() > screenH / 2 }
        if (big.isNotEmpty()) return "OK via a11y bounds=${big.first()}"
        val overlays = shell("dumpsys window windows").lines().count { it.contains("ty=APPLICATION_OVERLAY") }
        return if (overlays >= 2) "OK via dumpsys (APPLICATION_OVERLAY windows=$overlays, a11y=$windows)"
        else "MISSING a11y=$windows dumpsysOverlays=$overlays"
    }

    // ------------------------------------------------------------------ helpers

    private var sampleKind = "lines"

    private fun waitForSample(timeoutMs: Long): Boolean {
        val sel = when (sampleKind) {
            "paragraph" -> By.pkg(TEST_PKG).textContains("Gazo")
            "image" -> By.pkg(TEST_PKG).desc("sample-image")
            else -> By.pkg(TEST_PKG).text(SAMPLE_LINES[1])
        }
        return device.wait(Until.hasObject(sel), timeoutMs) == true
    }

    private fun reportUnits() {
        report("ocr: ${DebugInfo.lastOcr}")
        DebugInfo.lastUnits.forEachIndexed { i, u ->
            report("  unit[$i] [${u.lang}/${u.action}] '${u.original.replace('\n', ' ')}' -> '${u.translated}'")
        }
    }

    private fun scrollTo(sel: BySelector): UiObject2? {
        device.findObject(sel)?.let { return it }
        val sc = device.findObject(By.clazz("android.widget.ScrollView").pkg(PKG)) ?: return null
        repeat(10) {
            sc.scroll(Direction.DOWN, 0.5f)
            device.findObject(sel)?.let { return it }
        }
        return null
    }

    private fun stopServiceIfRunning() {
        if (!ScreenTranslateService.isRunning) return
        runCatching {
            ctx.startService(
                Intent(ctx, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP)
            )
        }.onFailure { report("stop service failed: $it") }
        waitUntil(10_000) { !ScreenTranslateService.isRunning }
    }

    private fun clickIfPresent(sel: BySelector, what: String) {
        device.wait(Until.findObject(sel), 1_500)?.let {
            report("clicking $what")
            runCatching { it.click() }
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return true
            SystemClock.sleep(250)
        }
        return runCatching(cond).getOrDefault(false)
    }

    private fun shell(cmd: String): String = runCatching { device.executeShellCommand(cmd).trim() }
        .getOrElse { "shell error: $it" }

    private fun shot(name: String) {
        step++
        val file = String.format("%s/%s-%02d-%s.png", SHOT_DIR, mode?.label ?: "setup", step, name)
        shell("screencap -p $file")
        report("screenshot $file")
    }

    private fun dumpHierarchy(name: String) {
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }
            val f = File(dir, "${mode?.label ?: "setup"}-$name.xml")
            f.outputStream().use { device.dumpWindowHierarchy(it) }
            report("hierarchy ${f.absolutePath}")
        }.onFailure { report("hierarchy dump failed: $it") }
    }

    private fun dumpState(where: String) {
        report(
            "STATE[$where] running=${ScreenTranslateService.isRunning} calls=${DebugInfo.translateCalls} " +
                "results=${DebugInfo.resultCount} blocks=${DebugInfo.lastBlocks} lastError=${DebugInfo.lastError}"
        )
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
    }
}

