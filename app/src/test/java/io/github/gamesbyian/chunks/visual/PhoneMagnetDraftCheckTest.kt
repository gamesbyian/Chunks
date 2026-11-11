package io.github.gamesbyian.chunks.visual

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.MagnetDraftCheck
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The installed phone Add form checks a magnet draft before Add. Nothing is
 * added in these tests: no Add tile is pressed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneMagnetDraftCheckTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val field get() = rule.onNode(hasSetTextAction() and hasText("Magnet or HTTP(S) torrent URL"))

    private fun openAdd() {
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
    }

    private fun addTile() = rule.onNode(hasClickAction() and
        (hasText("Add magnet and start") or hasText("Add magnet (metadata only)")))

    @Test fun mistypedHashIsExplainedAndAddIsUnavailable() {
        openAdd()
        field.performScrollTo().performTextInput("magnet:?xt=urn:btih:" + "a".repeat(39))
        rule.waitForIdle()
        rule.onNodeWithText(MagnetDraftCheck.BAD_HASH).assertExists()
        addTile().assertIsNotEnabled()
        rule.onNodeWithText("Seeding: your phone Seeding rules", substring = true).assertDoesNotExist()
    }

    @Test fun notAMagnetIsExplained() {
        openAdd()
        field.performScrollTo().performTextInput("ftp://example.invalid/file.torrent")
        rule.waitForIdle()
        rule.onNodeWithText(MagnetDraftCheck.NOT_A_MAGNET).assertExists()
        addTile().assertIsNotEnabled()
    }

    @Test fun validMagnetNamesItsTorrentBeforeAdd() {
        openAdd()
        field.performScrollTo().performTextInput(
            "magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4&dn=Sintel+trailer")
        rule.waitForIdle()
        rule.onNodeWithText("Magnet for: Sintel trailer").assertExists()
        rule.onNodeWithText("Hash dd8255ec…db1f6d1c · no trackers listed").assertExists()
        rule.onNodeWithText(MagnetDraftCheck.BAD_HASH).assertDoesNotExist()
        rule.onNodeWithText("Seeding: your phone Seeding rules choose a policy", substring = true).assertExists()
    }

    private fun copy(text: String) {
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("copied", text))
    }

    @Test fun copiedMagnetFillsAnEmptyAddOnceWhenItOpens() {
        val link = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=pasted"
        copy("  $link\n")
        openAdd()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(link)).assertExists()
        rule.onNodeWithText("Magnet for: pasted").assertExists()
        rule.onNodeWithText("Filled from your clipboard. Nothing is added until you confirm.").assertExists()
        // Undo empties the draft and the note; it is not re-filled while the form stays open.
        rule.onNode(hasText("Clear") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(link)).assertDoesNotExist()
        rule.onNodeWithText("Filled from your clipboard", substring = true).assertDoesNotExist()
    }

    @Test fun unrelatedClipboardTextIsNeverFilledIn() {
        copy("https://example.invalid/not-a-magnet and some other words")
        openAdd()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("https://example.invalid", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("Filled from your clipboard", substring = true).assertDoesNotExist()
    }

    @Test fun withPrefillOffPasteFillsOnlyWhenTapped() {
        val link = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=pasted"
        val prefs = rule.activity.getSharedPreferences("add-clipboard-prefill", Context.MODE_PRIVATE)
        val original = prefs.getBoolean("enabled", true)
        try {
            // The persistent opt-out is in central Options. Begin with an
            // unrelated clipboard text so no previously admitted Add draft
            // can be mistaken for a failed preference.
            org.junit.Assert.assertTrue(prefs.edit().putBoolean("enabled", true).commit())
            copy("unrelated text, not a magnet or torrent link")
            openAdd()
            rule.onNode(hasSetTextAction() and hasText(link)).assertDoesNotExist()
            rule.onNodeWithText("Options").performClick()
            rule.onNodeWithText("Fill in a copied magnet or torrent link when Add opens")
                .performScrollTo().performClick()
            rule.waitUntil(5_000) { !prefs.getBoolean("enabled", true) }
            copy(link)
            // Merely returning to Add cannot prefill against the opt-out,
            // grant submission authority, or revive an older draft.
            rule.onNodeWithText("+ Add").performClick()
            rule.waitForIdle()
            rule.onNode(hasSetTextAction() and hasText(link)).assertDoesNotExist()
            // A deliberate manual Paste still works with prefill disabled.
            rule.onNodeWithText("Paste link").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNode(hasSetTextAction() and hasText(link)).assertExists()
        } finally {
            (rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .clearPrimaryClip()
            org.junit.Assert.assertTrue(prefs.edit().putBoolean("enabled", original).commit())
        }
    }

    @Test fun aTypedDraftIsNeverReplacedByTheClipboard() {
        openAdd()
        val typed = "magnet:?xt=urn:btih:" + "c".repeat(40) + "&dn=typed"
        field.performScrollTo().performTextInput(typed)
        copy("magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=copied")
        rule.onNodeWithText("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(typed)).assertExists()
        rule.onNodeWithText("Magnet for: copied").assertDoesNotExist()
    }

    @Test fun returningToChunksFillsAMagnetCopiedElsewhere() {
        openAdd()
        rule.waitForIdle()
        val link = "magnet:?xt=urn:btih:" + "9".repeat(40) + "&dn=from-browser"
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        copy(link)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(link)).assertExists()
        rule.onNodeWithText("Filled from your clipboard. Nothing is added until you confirm.").assertExists()
        // Cleared once, the same copied magnet is not offered again on the next return.
        rule.onNode(hasText("Clear") and hasClickAction()).performScrollTo().performClick()
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(link)).assertDoesNotExist()
    }

    @Test fun aTorrentLinkIsShownRedactedAndFetchedOnlyOnRequest() {
        openAdd()
        field.performScrollTo().performTextInput("https://t.example/dl.php?id=7&passkey=SECRET123")
        rule.waitForIdle()
        rule.onNodeWithText("Torrent link: t.example · dl.php").assertExists()
        // Only the editable field itself holds the token; no summary repeats it.
        org.junit.Assert.assertEquals(1, rule.onAllNodes(hasText("SECRET123", substring = true)).fetchSemanticsNodes().size)
        rule.onNode(hasText("Get torrent from link") and hasClickAction()).assertExists()
        // A link is not a magnet: no magnet Add tile and no magnet problem.
        addTile().assertDoesNotExist()
        rule.onNodeWithText(MagnetDraftCheck.NOT_A_MAGNET).assertDoesNotExist()
    }

    @Test fun aPlainHttpLinkExplainsTheBrowserRoute() {
        openAdd()
        field.performScrollTo().performTextInput("http://t.example/a.torrent")
        rule.waitForIdle()
        rule.onNodeWithText(io.github.gamesbyian.chunks.TorrentLinkCheck.PLAIN_HTTP).assertExists()
        rule.onNode(hasText("Get torrent from link") and hasClickAction()).assertDoesNotExist()
    }
}
