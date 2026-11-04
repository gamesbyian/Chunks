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

    private val field get() = rule.onNode(hasSetTextAction() and hasText("Or paste a magnet link"))

    private fun openAdd() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
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
    }

    @Test fun notAMagnetIsExplained() {
        openAdd()
        field.performScrollTo().performTextInput("https://example.invalid/file.torrent")
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
    }

    @Test fun pasteFillsTheDraftOnlyWhenTapped() {
        val link = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=pasted"
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("magnet", "  $link\n"))
        openAdd()
        // Nothing is read before the tap.
        rule.onNode(hasSetTextAction() and hasText(link)).assertDoesNotExist()
        rule.onNodeWithText("Paste magnet link").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(link)).assertExists()
        rule.onNodeWithText("Magnet for: pasted").assertExists()
    }
}
