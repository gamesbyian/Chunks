package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android preferences and the production editor; no remote/network fixtures required. */
@RunWith(AndroidJUnit4::class)
class TransferNotesInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun saveReopenAndClearNotesWithoutCrossingClientScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferNotesStore(context)
        val hash = "61".repeat(20)
        val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remote = "https://test.invalid/RPC2\u0000private-note-native"
        val selected = mutableStateOf(local)
        try {
            store.save(local, hash, store.read(local, hash), "")
            store.save(remote, hash, store.read(remote, hash), "")
            rule.setContent {
                ChunksTheme {
                    Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                        TransferNotesSection(selected.value, hash)
                    }
                }
            }
            waitText("No private note")
            rule.onNodeWithText("Edit private note").performScrollTo().performClick()
            rule.onNodeWithTag("private-transfer-note-input").performTextInput("Local note")
            rule.onNodeWithText("Save private note").performScrollTo().performClick()
            waitText("Private note saved.")
            assertEquals("Local note", TransferNotesStore(context).read(local, hash))

            rule.runOnIdle { selected.value = remote }
            waitText("No private note")
            rule.onNodeWithText("Local note").assertDoesNotExist()
            rule.onNodeWithText("Edit private note").performScrollTo().performClick()
            rule.onNodeWithTag("private-transfer-note-input").performTextInput("Remote note")
            rule.onNodeWithText("Save private note").performScrollTo().performClick()
            waitText("Private note saved.")
            assertEquals("Remote note", TransferNotesStore(context).read(remote, hash))

            rule.runOnIdle { selected.value = local }
            waitText("Local note")
            rule.onNodeWithText("Remote note").assertDoesNotExist()
            rule.onNodeWithText("Edit private note").performScrollTo().performClick()
            rule.onNodeWithTag("private-transfer-note-input").performTextClearance()
            rule.onNodeWithText("Save empty note (clear)").performScrollTo().performClick()
            waitText("Private note cleared.")
            assertEquals("", TransferNotesStore(context).read(local, hash))
            assertEquals("Remote note", TransferNotesStore(context).read(remote, hash))
        } finally {
            store.save(local, hash, store.read(local, hash), "")
            store.save(remote, hash, store.read(remote, hash), "")
        }
    }
}
