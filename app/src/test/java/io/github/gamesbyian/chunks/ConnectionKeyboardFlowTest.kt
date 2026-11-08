package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Password Go runs the caller's Connect; without it the key stays Done. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class ConnectionKeyboardFlowTest {
    @get:Rule val rule = createComposeRule()

    private fun ime(action: ImeAction) = SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, action)

    @Test fun passwordGoInvokesTheSuppliedActionOnce() {
        var connects = 0
        rule.setContent { ChunksTheme { SecurePasswordInput("secret", {}, onGo = { connects++ }) } }
        rule.onNode(hasSetTextAction()).assert(ime(ImeAction.Go)).performImeAction()
        assertEquals(1, connects)
    }

    @Test fun passwordWithoutAnActionKeepsDone() {
        rule.setContent { ChunksTheme { SecurePasswordInput("secret", {}) } }
        rule.onNode(hasSetTextAction()).assert(ime(ImeAction.Done))
    }
}

/** On the real connection screen, address and username move to the next field. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionScreenImeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun addressAndUsernameOfferNext() {
        rule.waitForIdle()
        rule.onNodeWithText("Connections").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Seedbox hostname, ruTorrent URL or RPC address"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        rule.onNode(hasSetTextAction() and hasText("Username, if required"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
    }

    @Test fun phoneMagnetFieldClosesWithDone() {
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Or paste a magnet or torrent link"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        rule.onNode(hasSetTextAction() and hasText("Optional phone download subfolder"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }
}
