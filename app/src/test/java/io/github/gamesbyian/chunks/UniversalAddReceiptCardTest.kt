package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddDisplayProjector
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One rendered receipt vocabulary for local and authenticated remote clients. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class UniversalAddReceiptCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun changingClientOnlyChangesReceiptNameNotMeaningOrLayout() {
        val client = mutableStateOf("Local")
        rule.setContent {
            ChunksTheme {
                Surface {
                    val receipt = requireNotNull(UniversalAddDisplayProjector.from(
                        UniversalAddCoordinator.Phase.VERIFIED, client.value
                    ))
                    UniversalAddReceiptCard(receipt) {
                        androidx.compose.material3.Text("Read-only details")
                    }
                }
            }
        }
        rule.onNodeWithText("Added to Local").assertExists()
        rule.onNode(hasStateDescription("Verified")).assertExists()
        rule.onNodeWithText("This submission's exact transfer, destination and requested Start state are verified. Peer activity and payload bytes are separate.").assertExists()
        rule.onNodeWithText("Read-only details").assertExists()
        rule.runOnIdle { client.value = "Wood" }
        rule.onNodeWithText("Added to Wood").assertExists()
        rule.onNodeWithText("Added to Local").assertDoesNotExist()
        rule.onNodeWithText("Read-only details").assertExists()
    }

    @Test fun lostAckNeverReceivesAnAddedHeadingOrImplicitSuccess() {
        val receipt = requireNotNull(UniversalAddDisplayProjector.from(
            UniversalAddCoordinator.Phase.READBACK_REQUIRED, "Wood"
        ))
        rule.setContent {
            ChunksTheme {
                Surface {
                    UniversalAddReceiptCard(receipt, needsAttention = true)
                }
            }
        }
        rule.onNodeWithText("Couldn't confirm Add on Wood").assertExists()
        rule.onNode(hasStateDescription("Add unverified")).assertExists()
        rule.onNodeWithText("Added to Wood").assertDoesNotExist()
        rule.onNodeWithText(
            "The Add may have happened. Check this exact client's transfer state; never retry an uncertain write automatically."
        ).assertExists()
    }
}
