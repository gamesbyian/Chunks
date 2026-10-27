package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionNameManagerInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun createAndForgetEmptyCollectionNeverRemoveTorrentMembership() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = CollectionNameRegistry(ctx)
        val membership = TransferCollectionStore(ctx)
        val scope = "https://test.invalid/RPC2\u0000empty-collection-native-case"
        val infoHash = "7".repeat(40)
        val name = "Star Trek empty collection"
        registry.forget(scope, name)
        membership.remove(scope, infoHash, name)
        try {
            // A separately assigned membership demonstrates "Forget" semantics:
            // it removes only the reusable name, never any torrent affiliation.
            membership.add(scope, infoHash, name)
            rule.setContent {
                var registered by remember { mutableStateOf(registry.names(scope)) }
                MaterialTheme {
                    CollectionNameManager(scope, registry, registered) {
                        registered = it
                    }
                }
            }
            rule.onNodeWithTag("new-collection-name").performTextInput(name)
            rule.onNodeWithText("Create empty collection").performClick()
            rule.waitForIdle()
            assertEquals(listOf(name), registry.names(scope))
            rule.onNodeWithText("Manage saved names").performClick()
            rule.onNodeWithText("Forget saved name $name").performClick()
            rule.onNodeWithText("Confirm forget $name").performClick()
            rule.waitForIdle()
            assertEquals(emptyList<String>(), registry.names(scope))
            assertEquals(listOf(name), membership.forTransfer(scope, infoHash))
        } finally {
            registry.forget(scope, name)
            membership.remove(scope, infoHash, name)
        }
    }
}
