package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** On timeout, name what was expected and what the screen showed instead. */
    private fun waitOrExplain(what: String, ms: Long, condition: () -> Boolean) {
        try {
            rule.waitUntil(ms, condition)
        } catch (timeout: Throwable) {
            val shown = rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
                .take(40)
            throw AssertionError("Waited for $what; screen showed: $shown", timeout)
        }
    }

    @Test fun copiedMagnetPrefillsTheForegroundPhoneAdd() {
        val link = "magnet:?xt=urn:btih:" + "f".repeat(40) + "&dn=Installed%20clipboard"
        waitOrExplain("the phone screen", 30_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Phone transfers"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.runOnUiThread {
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
