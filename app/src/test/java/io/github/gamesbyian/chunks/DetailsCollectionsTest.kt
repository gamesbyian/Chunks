package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Details collections save membership only under the exact owning scope (never another environment's). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1200dp-xxhdpi")
class DetailsCollectionsTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun membershipIsSavedUnderTheExactScopeOnly() {
        val hash = "ef".repeat(20)
        val serverScope = "https://seed.example/RPC2\u0000user"
        rule.setContent { ChunksTheme { DetailsCollections(serverScope, hash, "Show S01") } }
        rule.onNodeWithText("Edit collections").performClick()
        rule.onNodeWithText("Collection name").performTextInput("Watch next")
        rule.onNodeWithText("Add to collection").performClick()
        val store = TransferCollectionStore(context)
        rule.waitUntil(5_000) { store.forTransfer(serverScope, hash).contains("Watch next") }
        assertTrue("never written under the local scope",
            store.forTransfer(TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash).isEmpty())
        rule.waitUntil(5_000) { CollectionNameRegistry(context).names(serverScope).contains("Watch next") }
        assertEquals(emptyList<String>(), CollectionNameRegistry(context).names(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
            .filter { it == "Watch next" })
    }
}
