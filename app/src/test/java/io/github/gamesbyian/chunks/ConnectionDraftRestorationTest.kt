package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Restoring a draft restores no password or permission to authenticate it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ConnectionDraftRestorationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nameAndAddressRestoreTogetherButPasswordDoesNot() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            ChunksTheme { ChunksApp(null, null, {}, {}) }
        }
        rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).performTextInput("Studio")
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
            .performTextInput("https://draft.invalid/RPC2")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Password"))
        rule.onNodeWithText("Password").performTextInput("unsaved-secret")

        restoration.emulateSavedInstanceStateRestore()

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connection name (optional)"))
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Studio")))
        rule.onNodeWithText("https://draft.invalid/RPC2").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Password"))
        rule.onNodeWithText("Password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Finding secure rTorrent…").assertDoesNotExist()
    }
}
