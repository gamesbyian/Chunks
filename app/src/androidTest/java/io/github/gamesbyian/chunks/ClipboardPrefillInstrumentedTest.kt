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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed app, real Android clipboard, app in the foreground: an empty
 * local Add takes a copied magnet as a draft and says where it came from.
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

    /** Recover one exact unrelated *system app* ANR, never an app failure.
     *
     * Run 42808797258 attempt 1 captured the full emulator window with an
     * Android "Messages isn't responding" overlay, while the Chunks-root
     * screenshot showed the expected healthy Transfers screen underneath.
     * The older reproducible Pixel Launcher ANR has the same focus impact.
     * Only these two exact observed system labels permit clicking "Wait".
     * Never dismiss a Chunks ANR, an unknown app, permission prompt or
     * generic overlay. Real activity focus and all clipboard/Add positive
     * assertions are still independently required after the dismissal.
     */
    private fun dismissExactUnrelatedSystemAnr(device: UiDevice): Boolean {
        val knownUnrelatedSystemApp = listOf(
            "Pixel Launcher isn't responding",
            "Messages isn't responding"
        ).any { device.findObject(By.text(it)) != null }
        if (!knownUnrelatedSystemApp) return false
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

    @Test fun copiedMagnetPrefillsTheForegroundLocalAdd() {
        val link = "magnet:?xt=urn:btih:" + "f".repeat(40) + "&dn=Installed%20clipboard"
        val local = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Transfers on this device")
        val entry = hasText("Transfers") and hasClickAction()
        // A login remembered by an earlier installed test starts on the server
        // entry instead; reach the local device the way a person would, from there.
        waitOrExplain("the local device screen or its entry", 30_000) {
            rule.onAllNodes(local).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onNodeWithText("Transfers").assertExists() }.isSuccess
        }
        if (rule.onAllNodes(local).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(entry).performClick()
            waitOrExplain("device or combined transfer list", 15_000) {
                rule.onAllNodes(local).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithText("Open device transfer controls").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodes(local).fetchSemanticsNodes().isEmpty()) {
                // A remembered server makes Transfers show the combined list.
                // Explicitly open the device inventory, without changing login.
                rule.onNode(hasScrollToIndexAction())
                    .performScrollToNode(hasText("Open device transfer controls"))
                rule.onNodeWithText("Open device transfer controls").performClick()
            }
            waitOrExplain("the local device screen", 15_000) {
                rule.onAllNodes(local).fetchSemanticsNodes().isNotEmpty()
            }
        }
        // The journey under test is "copy, then open Add": if the form is already
        // open on arrival, close it first so opening it is a real transition.
        // Transfers and Add are separate persistent destinations; ensure
        // prefill is tested on a genuinely new Add entry.
        if (rule.onAllNodes(local).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithText("Transfers").performClick()
            rule.waitForIdle()
        }
        // Real API35 snapshots show the unrelated system Pixel Launcher and
        // Messages ANR overlays can steal a healthy Chunks window's focus.
        // Allow only one *exact* system "Wait", and assert real focus before
        // any clipboard or Add action. Every product assertion is preserved. Never
        // override the enabled/usable clipboard or positive UI assertions.
        val device = UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        var unrelatedSystemDialogDismissed = false
        var nextDialogProbeAt = 0L
        waitOrExplain("foreground window focus", 15_000) {
            if (rule.activity.hasWindowFocus()) true else {
                val now = SystemClock.elapsedRealtime()
                if (!unrelatedSystemDialogDismissed && now >= nextDialogProbeAt) {
                    nextDialogProbeAt = now + 500L
                    unrelatedSystemDialogDismissed = dismissExactUnrelatedSystemAnr(device)
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
            rule.onNodeWithText("+ Add").performClick()
            // The connected-server chooser is a separate screen; make the
            // device choice explicit, and retain the original clipboard proof.
            if (rule.onAllNodesWithText("On this device").fetchSemanticsNodes().isNotEmpty())
                rule.onNodeWithText("On this device").performClick()
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
