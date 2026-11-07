package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed app, real Android clipboard, app in the foreground: an empty
 * phone Add takes a copied magnet as a draft and says where it came from.
 * Nothing is added; the Add tile is never pressed.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardPrefillInstrumentedTest {
    // Queue frame continuations on the Compose test scheduler. The legacy eager
    // scheduler can resume Android ripple work on an IO completion thread.
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** On timeout, name what was expected and what the screen showed instead. */
    private fun waitOrExplain(what: String, ms: Long, condition: () -> Boolean) {
        try {
            rule.waitUntil(ms, condition)
        } catch (timeout: Throwable) {
            val shown = rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
                .take(40)
            // The CI receipt omits exception text; a screenshot in the uploaded atlas folder shows the state.
            // Retain only booleans in the screenshot name; never export clipboard contents.
            var diagnosis = ""
            rule.runOnUiThread {
                diagnosis = "-enabled-${ClipboardMagnetPrefill.enabled(rule.activity)}" +
                    "-focus-${rule.activity.hasWindowFocus()}" +
                    "-usable-${ClipboardMagnetPrefill.decide(true, "", false, ClipboardMagnetPrefill.readText(rule.activity)) != null}" +
                    "-intent-source-${rule.activity.intent?.let { it.data != null || it.hasExtra(android.content.Intent.EXTRA_STREAM) || it.hasExtra(android.content.Intent.EXTRA_TEXT) } == true}"
            }
            runCatching { saveDiagnostic("clipboard-diagnostic-" + what.replace(Regex("[^a-z]+"), "-") + diagnosis) }
            throw AssertionError("Waited for $what; screen showed: $shown", timeout)
        }
    }

    /** Only an unrelated emulator launcher ANR may be dismissed here.
     *
     * A system-process ANR dialog takes window focus from a healthy test
     * Activity. It is not an application Add/authorization result. Never
     * dismiss a Chunks ANR, a permission prompt or an arbitrary overlay,
     * and never count this recovery as a passed clipboard interaction.
     */
    private fun dismissExactLauncherAnr(device: UiDevice): Boolean {
        if (device.findObject(By.text("Pixel Launcher isn't responding")) == null) return false
        val wait = device.findObject(By.text("Wait")) ?: return false
        return runCatching { wait.click(); true }.getOrDefault(false)
    }

    private fun saveDiagnostic(name: String) {
        val image = rule.onNode(androidx.compose.ui.test.isRoot()).captureToImage().asAndroidBitmap()
        saveBitmap(name, image)
        // A composed background Activity can lack focus because another system
        // window is on top. Keep the full installed display as well as the app
        // root, so a recurring pre-write focus failure can be diagnosed. These
        // controlled PR fixtures contain no protected credentials/clipboard.
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.takeScreenshot()?.let { saveBitmap("$name-system-window", it) }
        }
    }

    private fun saveBitmap(name: String, image: android.graphics.Bitmap) {
        val resolver = rule.activity.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun copiedMagnetPrefillsTheForegroundPhoneAdd() {
        val link = "magnet:?xt=urn:btih:" + "f".repeat(40) + "&dn=Installed%20clipboard"
        val phone = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Phone transfers")
        val entry = hasText("Open phone downloads") and hasClickAction()
        // A login remembered by an earlier installed test starts on the server
        // entry instead; reach the phone the way a person would, from there.
        waitOrExplain("the phone screen or its entry", 30_000) {
            rule.onAllNodes(phone).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open phone downloads")) }.isSuccess
        }
        if (rule.onAllNodes(phone).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(entry).performScrollTo().performClick()
            waitOrExplain("the phone screen", 15_000) { rule.onAllNodes(phone).fetchSemanticsNodes().isNotEmpty() }
        }
        // The journey under test is "copy, then open Add": if the form is already
        // open on arrival, close it first so opening it is a real transition.
        val hideAdd = hasText("Hide phone Add options") and hasClickAction()
        if (rule.onAllNodes(hideAdd).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hideAdd).performScrollTo().performClick()
            rule.waitForIdle()
        }
        // Actual API35 GHA captures on two unrelated source heads show the
        // *system launcher* ANR overlay stealing foreground focus. Before
        // asserting clipboard availability, permit one precise external
        // "Wait" dismissal. Recheck real Chunks focus afterward. Never
        // override the enabled/usable clipboard or positive UI assertions.
        val device = UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        var launcherDialogDismissed = false
        var nextDialogProbeAt = 0L
        waitOrExplain("foreground window focus", 15_000) {
            if (rule.activity.hasWindowFocus()) true else {
                val now = SystemClock.elapsedRealtime()
                if (!launcherDialogDismissed && now >= nextDialogProbeAt) {
                    nextDialogProbeAt = now + 500L
                    launcherDialogDismissed = dismissExactLauncherAnr(device)
                }
                false
            }
        }
        rule.runOnUiThread {
            org.junit.Assert.assertTrue("Clipboard prefill must be enabled for this journey", ClipboardMagnetPrefill.enabled(rule.activity))
            (rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("magnet", link))
        }
        try {
            rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
            waitOrExplain("the prefilled magnet", 10_000) {
                rule.onAllNodes(hasSetTextAction() and hasText(link)).fetchSemanticsNodes().isNotEmpty()
            }
            waitOrExplain("the clipboard note", 5_000) {
                rule.onAllNodes(hasText("Filled from your clipboard. Nothing is added until you confirm."))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            // Never leave a magnet for another installed test's empty Add field.
            rule.runOnUiThread {
                (rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
            }
        }
    }
}
