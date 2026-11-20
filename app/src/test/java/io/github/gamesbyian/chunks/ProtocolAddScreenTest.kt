package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.TorrentClient
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolAddScreenTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "cd".repeat(20)

    private inner class Fake(
        val levels: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()
    ) : TorrentClient {
        override val kind = TorrentClientKind.DELUGE
        var added: ClientAddRequest? = null
        var writes = 0
        var loseNextWrite = false
        /** The write lands on the client, but its reply is lost. */
        var landThenLoseNextWrite = false
        var reads = 0
        override suspend fun session() = ClientSessionInfo(kind, "2.1", "/srv/dl", ClientCapability.entries.toSet(), levels)
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String): ClientTorrent? = also { reads++ }.added?.takeIf { hash == this@ProtocolAddScreenTest.hash }?.let {
            ClientTorrent(hash, "x", if (it.startNow) ClientRunState.DOWNLOADING else ClientRunState.STOPPED,
                null, null, null, null, it.directory ?: "/srv/dl")
        }
        override suspend fun files(hash: String) = emptyList<io.github.gamesbyian.chunks.rpc.ClientFile>()
        override suspend fun add(request: ClientAddRequest) {
            writes++
            if (loseNextWrite) { loseNextWrite = false; throw io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException("lost") }
            added = request
            if (landThenLoseNextWrite) { landThenLoseNextWrite = false; throw io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException("lost") }
        }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    @Test fun stoppedMagnetAddIsSubmittedOnceAndConfirmedByReadback() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val start = mutableStateOf(false)
        val draft = mutableStateOf("")
        val torrent = mutableStateOf<PendingLocalAdd?>(null)
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box Deluge",
                    createOperations = { ClientOperations(fake, 3, 1) },
                    destinationPicker = {},
                    sourceDraft = draft, reviewedTorrent = torrent,
                    startState = start, attempt = attempt, attemptScope = androidx.compose.runtime.rememberCoroutineScope(),
                    onViewTransfer = {}
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextInput("magnet:?xt=urn:btih:$hash&dn=x")
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo()
            .assertIsEnabled().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        // The one shared receipt: verified by the coordinator's evidence, not prose.
        rule.onNodeWithText("Added to Box Deluge").performScrollTo()
        rule.onNode(androidx.compose.ui.test.hasStateDescription("Verified")).assertExists()
        assertEquals(1, fake.writes)
        assertFalse(fake.added!!.startNow)
        assertEquals(hash, (fake.added!!.source as ClientAddSource.Magnet).infoHash)
        // A settled attempt must be explicitly cleared; no second submission.
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, fake.writes)
    }

    @Test fun addTimeHighIsOfferedOnlyAtLevelsTheClientStoresAndFrozenIntoTheOneAdd() {
        // Deluge-like: no distinct Low. The cycle is Normal -> High -> Normal only.
        val fake = Fake(levels = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        val bytes = io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.build(
            io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Request("pack", listOf(
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(listOf("one.bin"), 20_000L) {
                    ByteArray(20_000).inputStream() },
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(listOf("two.bin"), 20_000L) {
                    ByteArray(20_000) { 1 }.inputStream() }),
                trackerTiers = emptyList(), pieceLength = 16 * 1024)).torrent
        val torrent = mutableStateOf<PendingLocalAdd?>(
            PendingLocalAdd(io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes), bytes))
        val attempt = ProtocolAddAttempt()
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box Deluge",
                    createOperations = { ClientOperations(fake, 3, 1) },
                    destinationPicker = {},
                    sourceDraft = mutableStateOf(""), reviewedTorrent = torrent,
                    startState = mutableStateOf(false), attempt = attempt,
                    attemptScope = androidx.compose.runtime.rememberCoroutineScope(),
                    onViewTransfer = {}
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_FILE_CHOICE).performScrollTo().performClick()
        rule.waitForIdle()
        val one = rule.onNodeWithTag(ChunksSemantics.ADD_FILE_PRIORITY + ".0")
        one.performScrollTo().assertTextEquals("Normal").performClick()
        rule.waitForIdle()
        one.assertTextEquals("High").performClick()
        rule.waitForIdle()
        one.assertTextEquals("Normal").performClick()   // never Low on this client
        rule.waitForIdle()
        one.assertTextEquals("High")
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        assertEquals("one Add", 1, fake.writes)
        val sent = fake.added!!
        assertEquals(setOf(0), sent.filePriorities.keys)
        assertEquals(ClientFilePriority.HIGH, sent.filePriorities.getValue(0).priority)
        assertEquals("one.bin", sent.filePriorities.getValue(0).path.substringAfterLast('/'))
        assertEquals(emptyMap<Int, String>(), sent.skippedFiles)
    }

    private fun renderMagnetAdd(fake: Fake, attempt: ProtocolAddAttempt, draft: androidx.compose.runtime.MutableState<String>) {
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box Deluge",
                    createOperations = { ClientOperations(fake, 3, 1) },
                    destinationPicker = {},
                    sourceDraft = draft, reviewedTorrent = mutableStateOf(null),
                    startState = mutableStateOf(false), attempt = attempt,
                    attemptScope = androidx.compose.runtime.rememberCoroutineScope(),
                    onViewTransfer = {}
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun anUnresolvedAddCannotBeDismissedOrSubmittedAgain() {
        val fake = Fake().apply { loseNextWrite = true }
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=x")
        renderMagnetAdd(fake, attempt, draft)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        assertEquals(1, fake.writes)
        // Add is unavailable while the uncertain write is unresolved: no blind second write.
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals("no blind second write after an uncertain Add", 1, fake.writes)
        rule.onNodeWithText("Couldn't confirm Add on Box Deluge").assertExists()
        rule.onNodeWithTag(ChunksSemantics.ADD_ANOTHER).assertDoesNotExist()
        rule.runOnIdle { assertFalse(attempt.clearSettled("p")) }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled()
        assertEquals("no second write after an attempted reset", 1, fake.writes)
    }

    @Test fun aVerifiedAddDoesNotBlockTheNextReviewedSource() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=x")
        renderMagnetAdd(fake, attempt, draft)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome is ClientOperationOutcome.Verified }
        val other = "ef".repeat(20)
        rule.runOnIdle { draft.value = "magnet:?xt=urn:btih:$other&dn=y"; fake.added = null }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { fake.writes == 2 }
        assertEquals(other, (fake.added!!.source as ClientAddSource.Magnet).infoHash)
    }

    @Test fun acknowledgingOldProtocolAddKeepsNewerMagnetDraft() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=old")
        renderMagnetAdd(fake, attempt, draft)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome is ClientOperationOutcome.Verified }
        val newer = "magnet:?xt=urn:btih:" + "ef".repeat(20) + "&dn=new"
        rule.runOnIdle { draft.value = newer }
        rule.onNodeWithTag(ChunksSemantics.ADD_ANOTHER).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("An older receipt may not erase a newer shared magnet", newer, draft.value)
            assertEquals("No second Add until a new explicit submission", 1, fake.writes)
        }
    }

    @Test fun submittedProtocolSourceFingerprintIsExactAndCannotEraseANewDocument() {
        val bytes = io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.build(
            io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Request("exact-review",
                listOf(io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(
                    listOf("one.bin"), 1024L) { ByteArray(1024).inputStream() }),
                trackerTiers = emptyList(), pieceLength = 16 * 1024)).torrent
        val preview = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes)
        val first = PendingLocalAdd(preview, bytes)
        val attempt = ProtocolAddAttempt()
        val selectedText = "magnet:?xt=urn:btih:$hash&tr=https%3A%2F%2Fprivate.one"
        assertTrue(attempt.claim("p", hash, selectedText, first))
        assertTrue(attempt.submittedTextStillSelected(selectedText))
        assertTrue(attempt.submittedFileStillSelected(first))
        assertFalse(attempt.submittedTextStillSelected(
            "magnet:?xt=urn:btih:$hash&tr=https%3A%2F%2Fprivate.two"))
        // A newly reviewed file object, even with the same infohash and
        // identical bytes, is a distinct user source and must survive.
        assertFalse(attempt.submittedFileStillSelected(PendingLocalAdd(preview, bytes.copyOf())))
        first.bytes[0] = (first.bytes[0].toInt() xor 1).toByte()
        assertFalse(attempt.submittedFileStillSelected(first))
        attempt.releaseUnwritten(hash)
        assertFalse(attempt.submittedTextStillSelected(selectedText))
        assertFalse(attempt.submittedFileStillSelected(first))
    }

    @Test fun anotherClientsAttemptBlocksSubmission() {
        val attempt = ProtocolAddAttempt().apply { claim("other", hash) }
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash")
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box", createOperations = { ClientOperations(Fake(), 3, 1) },
                    destinationPicker = {}, sourceDraft = draft,
                    reviewedTorrent = mutableStateOf(null), startState = mutableStateOf(true),
                    attempt = attempt, attemptScope = MainScope(), onViewTransfer = {}
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test fun checkStatusReadsOnceAndConfirmsALandedJobWithoutASecondWrite() {
        val fake = Fake().apply { landThenLoseNextWrite = true }
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=x")
        renderMagnetAdd(fake, attempt, draft)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't confirm Add on Box Deluge").assertExists()
        val readsBefore = fake.reads
        rule.onNodeWithTag(ChunksSemantics.ADD_CHECK_STATUS).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.checkNote != null }
        rule.waitForIdle()
        // Exact job, folder and stopped state are confirmed, never attributed to this Add.
        rule.onNodeWithText("Found on Box Deluge").performScrollTo()
        assertEquals("one read-only exact-hash read", readsBefore + 1, fake.reads)
        assertEquals("never a second write", 1, fake.writes)
        rule.onNodeWithTag(ChunksSemantics.ADD_CHECK_STATUS).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled()
        // Acknowledging the confirmed session retires the ticket without resending.
        rule.onNodeWithTag(ChunksSemantics.ADD_ANOTHER).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.EMPTY,
            attempt.coordinator("p").phase)
        assertEquals(1, fake.writes)
    }

    @Test fun checkStatusOfAJobThatNeverLandedKeepsTheFence() {
        val fake = Fake().apply { loseNextWrite = true }
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=x")
        renderMagnetAdd(fake, attempt, draft)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.onNodeWithTag(ChunksSemantics.ADD_CHECK_STATUS).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.checkNote != null }
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't confirm Add on Box Deluge").assertExists()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled()
        assertEquals(io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.READBACK_REQUIRED,
            attempt.coordinator("p").phase)
        assertEquals(1, fake.writes)
    }
}
