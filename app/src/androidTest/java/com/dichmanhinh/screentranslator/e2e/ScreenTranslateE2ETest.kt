package com.dichmanhinh.screentranslator.e2e

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.dichmanhinh.screentranslator.DebugInfo
import com.dichmanhinh.screentranslator.GoogleTranslator
import com.dichmanhinh.screentranslator.Prefs
import com.dichmanhinh.screentranslator.ScreenTranslateService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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

    // ------------------------------------------------------------------ flow

    private fun runE2E(m: Mode) {
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
        shell("am start -W -n $SAMPLE --ez tick $tick")
        assertTrue("sample app not shown", waitForSample(15_000))
        shot("sample")

        // C. Translator app -> Start.
        shell("am start -W -n $MAIN")
        val start = device.wait(Until.findObject(By.res(PKG, "btnStart")), 15_000)
        assertTrue("btnStart not found", start != null)
        shot("main")
        val resultsBefore = DebugInfo.resultCount
        val callsBefore = DebugInfo.translateCalls
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
            shell("am start -W -n $SAMPLE --ez tick $tick")
        }
        assertTrue("sample app not in foreground after consent", waitForSample(10_000))

        // F. Tap the floating bubble.
        SystemClock.sleep(1_500) // let the VirtualDisplay deliver its first frame
        val bubble = findBubble()
        report("bubble found via a11y=${bubble != null} bounds=${bubble?.visibleBounds}")
        shot("before-tap")
        tapBubble(bubble)
        if (!waitUntil(5_000) { DebugInfo.translateCalls > callsBefore }) {
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
        val matched = SAMPLE_LINES.count { seen.contains(it.lowercase()) }
        report("sample phrases recognized: $matched/${SAMPLE_LINES.size}")
        assertTrue("OCR did not read the sample app (matched=$matched): $originals", matched >= 2)

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

    private fun waitForSample(timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.pkg(TEST_PKG).text(SAMPLE_LINES[1])), timeoutMs) == true

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

