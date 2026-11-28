package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferDetails
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1200dp-xxhdpi")
class TransferNotesTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "a".repeat(40)
    private val remote = "https://test.invalid/RPC2\u0000account"
    private val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE

    @Test fun notesPersistAndConflictingEditsCannotOverwriteAnotherSavedNote() {
        val first = TransferNotesStore(context)
        val second = TransferNotesStore(context)
        assertEquals("", first.read(remote, hash))
        assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
            first.save(remote, hash, "", "Original\nSecond line").status)
        assertEquals("Original\nSecond line", second.read(remote, hash.uppercase()))
        assertEquals("", second.read(remote + " ", hash))
        assertEquals("", second.read(local, hash))
        assertEquals("", second.read(remote, "b".repeat(40)))
        assertEquals(TransferNotesStore.SaveStatus.CONFLICT,
            second.save(remote, hash, "", "Stale editor").status)
        assertEquals("Original\nSecond line", first.read(remote, hash))
        assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
            second.save(remote, hash, "Original\nSecond line", "").status)
        assertEquals("", TransferNotesStore(context).read(remote, hash))
    }

    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun canonicalDetailsSavesExactNoteAndLockDisposesPrivateDraft() {
        val key = UnifiedTransferKey.of(EnvironmentId("rtorrent:note-test"), hash)
        fun detail(availability: EnvironmentAvailability) = UnifiedTransferDetails.resolve(key,
            listOf(EnvironmentTransferSnapshot(key.environment, availability, true, 100L,
                if (availability == EnvironmentAvailability.LOCKED) emptyList() else listOf(
                    UnifiedTransferObservation(key, "Sample", "Sample", 100L, 500_000, false,
                        0L, 0L, null)))), null, 110L)
        val selected = mutableStateOf(detail(EnvironmentAvailability.LIVE))
        val notes = TransferNotesStore(context)
        try {
            rule.setContent {
                ChunksTheme {
                    UnifiedTransferDetailPanel(selected.value, "Remote test", onBack = {},
                        collections = { transfer -> DetailsCollections(remote, transfer.key.infoHash, transfer.displayName) })
                }
            }
            waitText("No private note")
            rule.onNodeWithText("Edit private note").performScrollTo().performClick()
            rule.onNodeWithTag("private-transfer-note-input").performScrollTo().performTextInput("Saved privately")
            rule.onNodeWithText("Save private note").performScrollTo().performClick()
            waitText("Private note saved.")
            assertEquals("Saved privately", TransferNotesStore(context).read(remote, hash))
            assertEquals("", notes.read(local, hash))
            rule.onNodeWithText("Edit private note").performScrollTo().performClick()
            rule.onNodeWithTag("private-transfer-note-input").performScrollTo().performTextInput(" unsaved draft")
            rule.runOnIdle { selected.value = detail(EnvironmentAvailability.LOCKED) }
            rule.onNodeWithText("Private note").assertDoesNotExist()
            rule.onNodeWithText("Saved privately", substring = true).assertDoesNotExist()
            rule.onNodeWithTag("private-transfer-note-input").assertDoesNotExist()
            rule.runOnIdle { selected.value = detail(EnvironmentAvailability.LIVE) }
            waitText("Saved privately")
            rule.onNodeWithTag("private-transfer-note-input").assertDoesNotExist()
            rule.onNodeWithText("unsaved draft", substring = true).assertDoesNotExist()
            assertEquals("Saved privately", notes.read(remote, hash))
        } finally {
            notes.save(remote, hash, notes.read(remote, hash), "")
        }
    }
}
