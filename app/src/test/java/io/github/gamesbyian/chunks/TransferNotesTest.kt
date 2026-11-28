package io.github.gamesbyian.chunks

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertEquals("", first.read(remote, hash).text)
        assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
            first.save(remote, hash, "", "Original\nSecond line").status)
        assertEquals("Original\nSecond line", second.read(remote, hash.uppercase()).text)
        assertEquals("", second.read(remote + " ", hash).text)
        assertEquals("", second.read(local, hash).text)
        assertEquals("", second.read(remote, "b".repeat(40)).text)
        assertEquals(TransferNotesStore.SaveStatus.CONFLICT,
            second.save(remote, hash, "", "Stale editor").status)
        assertEquals("Original\nSecond line", first.read(remote, hash).text)
        assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
            second.save(remote, hash, "Original\nSecond line", "").status)
        assertEquals("", TransferNotesStore(context).read(remote, hash).text)
    }

    @Test fun corruptNotesAreNotSilentlyClearedOrOverwritten() {
        val scope = remote + "-corrupt"
        val prefKey = "note_" + MessageDigest.getInstance("SHA-256")
            .digest((scope + "\u0000" + hash.uppercase(java.util.Locale.ROOT)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val prefs = context.getSharedPreferences("chunks_private_transfer_notes_v1", android.content.Context.MODE_PRIVATE)
        val store = TransferNotesStore(context)
        try {
            assertTrue(prefs.edit().putInt(prefKey, 9).commit())
            try {
                store.read(scope, hash)
                fail("Malformed note must be unavailable")
            } catch (_: ClassCastException) {
                // Expected: wrong type is not an absent note.
            }
            try {
                store.save(scope, hash, "", "Do not replace")
                fail("Malformed note must not become a writable empty note")
            } catch (_: ClassCastException) {
                // Expected: original data is retained.
            }
            assertEquals(9, prefs.getInt(prefKey, 0))
        } finally {
            prefs.edit().remove(prefKey).commit()
        }
    }

    @Test fun failedCommitCannotBecomeVerifiedFromTheProcessCacheOrSameValueShortcut() {
        val scope = remote + "-uncertain"
        val prefs = context.getSharedPreferences("chunks_private_transfer_notes_v1", Context.MODE_PRIVATE)
        var reportFailure = true
        var commitCount = 0
        val injected = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "chunks_private_transfer_notes_v1")
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = prefs.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun commit(): Boolean {
                                commitCount++
                                assertTrue(delegate.commit())
                                return !reportFailure
                            }
                        }
                    }
                }
            }
        }
        val writer = TransferNotesStore(injected)
        val reader = TransferNotesStore(context)
        try {
            assertEquals(TransferNotesStore.SaveStatus.UNVERIFIED,
                writer.save(scope, hash, "", "Possibly saved").status)
            assertEquals(TransferNotesStore.Observation("Possibly saved", true), reader.read(scope, hash))
            assertFalse(reader.read(local, hash).writeUnverified)
            assertEquals(TransferNotesStore.SaveStatus.UNVERIFIED,
                writer.save(scope, hash, "Possibly saved", "Possibly saved").status)
            assertEquals(2, commitCount)
            assertTrue(reader.read(scope, hash).writeUnverified)

            reportFailure = false
            assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
                writer.save(scope, hash, "Possibly saved", "Possibly saved").status)
            assertEquals(3, commitCount)
            assertEquals(TransferNotesStore.Observation("Possibly saved", false), reader.read(scope, hash))

            reportFailure = true
            assertEquals(TransferNotesStore.SaveStatus.UNVERIFIED,
                writer.save(scope, hash, "Possibly saved", "").status)
            assertEquals(TransferNotesStore.Observation("", true), reader.read(scope, hash))
            reportFailure = false
            assertEquals(TransferNotesStore.SaveStatus.VERIFIED,
                writer.save(scope, hash, "", "").status)
            assertEquals(5, commitCount)
            assertEquals(TransferNotesStore.Observation("", false), reader.read(scope, hash))
        } finally {
            reportFailure = false
            writer.save(scope, hash, reader.read(scope, hash).text, "")
        }
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
            assertEquals("Saved privately", TransferNotesStore(context).read(remote, hash).text)
            assertEquals("", notes.read(local, hash).text)
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
            assertEquals("Saved privately", notes.read(remote, hash).text)
        } finally {
            notes.save(remote, hash, notes.read(remote, hash).text, "")
        }
    }
}
