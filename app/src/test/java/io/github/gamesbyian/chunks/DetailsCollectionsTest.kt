package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferDetails
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual canonical Details metadata slot, not an unconstrained pair of sibling cards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xhdpi")
class DetailsCollectionsTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "ef".repeat(20)
    private val serverScope = "https://seed.example/RPC2\u0000user"
    private val key = UnifiedTransferKey.of(EnvironmentId("collection-details-test"), hash)

    private fun detail(availability: EnvironmentAvailability) = UnifiedTransferDetails.resolve(key,
        listOf(EnvironmentTransferSnapshot(key.environment, availability, true, 100L,
            if (availability == EnvironmentAvailability.LOCKED) emptyList() else listOf(
                UnifiedTransferObservation(key, "Show S01", "Show S01", 100L, 500_000,
                    false, 0L, 0L, null)))), null, 110L)

    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun membershipIsSavedUnderTheExactScopeOnly() {
        val store = TransferCollectionStore(context)
        val registry = CollectionNameRegistry(context)
        try {
            rule.setContent {
                ChunksTheme {
                    Surface {
                        UnifiedTransferDetailPanel(detail(EnvironmentAvailability.LIVE), "Test client",
                            onBack = {}, collections = { transfer ->
                                DetailsCollections(serverScope, transfer.key.infoHash, transfer.displayName)
                            })
                    }
                }
            }
            waitText("No manual collections")
            rule.onNodeWithText("Edit collections").performScrollTo().performClick()
            // A label is not necessarily the editable semantics node. Require the
            // production field tag, real SetText action and enabled state.
            val field = rule.onNodeWithTag("collection-name-input")
            field.performScrollTo().assertIsEnabled().assert(hasSetTextAction())
            field.performTextInput("Watch next")
            rule.onNodeWithText("Add to collection").performScrollTo().assertIsEnabled().performClick()
            waitText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Watch next"), store.readForEdit(serverScope, hash))
            assertTrue("never written under the local scope",
                store.forTransfer(TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash).isEmpty())
            rule.waitUntil(10_000) { registry.names(serverScope).contains("Watch next") }
            assertEquals(emptyList<String>(), registry.names(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
                .filter { it == "Watch next" })
            rule.onNodeWithText("Membership saved on this device. Sharing is unchanged.")
                .performScrollTo().assertExists()
            rule.onRoot().captureRoboImage("build/chunks-visual/details-collection-saved-412x892.png")
        } finally {
            store.remove(serverScope, hash, "Watch next")
            registry.forget(serverScope, "Watch next")
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h700dp-xhdpi")
    fun narrowLargeTextCollectionEditorIsReachableAndLockDiscardsDraft() {
        val selected = mutableStateOf(detail(EnvironmentAvailability.LIVE))
        rule.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                ChunksTheme {
                    Surface {
                        UnifiedTransferDetailPanel(selected.value, "Test client", onBack = {},
                            collections = { transfer ->
                                DetailsCollections(serverScope, transfer.key.infoHash, transfer.displayName)
                            })
                    }
                }
            }
        }
        waitText("No manual collections")
        rule.onNodeWithText("Edit collections").performScrollTo().performClick()
        val field = rule.onNodeWithTag("collection-name-input")
        field.performScrollTo().assertIsEnabled().assert(hasSetTextAction())
        field.performTextInput("Unsaved draft")
        rule.onNodeWithText("Add to collection").performScrollTo().assertIsEnabled()
        rule.onRoot().captureRoboImage("build/chunks-visual/details-collection-edit-320x700-200pct.png")
        rule.runOnIdle { selected.value = detail(EnvironmentAvailability.LOCKED) }
        rule.onNodeWithTag("collection-name-input").assertDoesNotExist()
        rule.onNodeWithText("Unsaved draft", substring = true).assertDoesNotExist()
        rule.runOnIdle { selected.value = detail(EnvironmentAvailability.LIVE) }
        waitText("No manual collections")
        rule.onNodeWithText("Edit collections").performScrollTo().performClick()
        rule.onNodeWithTag("collection-name-input").performScrollTo().assertIsEnabled()
            .assert(hasSetTextAction())
        rule.onNodeWithText("Unsaved draft", substring = true).assertDoesNotExist()
        assertTrue(TransferCollectionStore(context).readForEdit(serverScope, hash).isEmpty())
    }
}
