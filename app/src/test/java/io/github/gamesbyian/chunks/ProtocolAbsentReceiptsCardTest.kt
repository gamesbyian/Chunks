package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #2026: a retained receipt whose job is no longer listed cannot be reached
 * from Details. Transfers offers its read-only check; it must read exactly
 * that hash once, never write, and release only that receipt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolAbsentReceiptsCardTest {
    @get:Rule val rule = createComposeRule()
    private val removed = "cd".repeat(20)
    private val listed = "ab".repeat(20)
    private val profile = "profile-a"

    private class Probe(var present: Set<String> = emptySet()) : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        val reads = mutableListOf<String>()
        var writes = 0
        var failRead: Throwable? = null
        var answerHash: String? = null
        override suspend fun session() = ClientSessionInfo(kind, "4", "/d", ClientCapability.entries.toSet())
        override suspend fun torrents() = present.map { row(it) }
        override suspend fun torrent(hash: String): ClientTorrent? {
            reads += hash
            failRead?.let { throw it }
            return if (hash in present) row(answerHash ?: hash) else null
        }
        private fun row(hash: String) = ClientTorrent(hash, "n", ClientRunState.STOPPED, 1, 0, 0, 0, "/d")
        override suspend fun files(hash: String) = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL))
        override suspend fun add(request: ClientAddRequest) { writes++ }
        override suspend fun setStarted(hash: String, started: Boolean) { writes++ }
        override suspend fun remove(hash: String, deleteData: Boolean) { writes++ }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) { writes++ }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) { writes++ }
        override suspend fun recheck(hash: String) { writes++ }
        override suspend fun transferSource(hash: String) =
            io.github.gamesbyian.chunks.rpc.ClientMagnets.build(hash, "n", emptyList())
    }

    /** A Remove whose reply was lost: possible write, then UNKNOWN. */
    private fun unknownRemove(owner: ProtocolOperationReceiptOwner, hash: String): ProtocolOperationReceiptOwner.Key {
        val key = ProtocolOperationReceiptOwner.Key(profile, hash)
        val ticket = requireNotNull(owner.begin(key, "g1", ProtocolOperationKind.REMOVE))
        assertTrue(owner.possibleWrite(ticket))
        owner.cancel(ticket)
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
        return key
    }

    private fun source(probe: Probe, listedHashes: Set<String>, current: () -> Boolean = { true }) =
        ProtocolReceiptCheckSource(profile, "Seedbox T", listedHashes, { ClientOperations(probe, 3, 1) }, current)

    @Test fun onlyCheckableReceiptsOfAbsentJobsOfThatProfileAreOffered() {
        val owner = ProtocolOperationReceiptOwner()
        unknownRemove(owner, removed)
        unknownRemove(owner, listed)
        val other = ProtocolOperationReceiptOwner.Key("profile-b", "ef".repeat(20))
        owner.begin(other, "g", ProtocolOperationKind.STOP) // PREFLIGHT: in flight, never checkable
        assertEquals(listOf(removed),
            absentJobReceipts(owner, profile, setOf(listed)).map { it.ticket.key.hash })
        assertTrue(absentJobReceipts(owner, "profile-b", emptySet()).isEmpty())
    }

    @Test fun readOnlyCheckReleasesOnlyTheExactAbsentReceiptWithoutAnyWrite() {
        val owner = ProtocolOperationReceiptOwner()
        val absentKey = unknownRemove(owner, removed)
        val listedKey = unknownRemove(owner, listed)
        val probe = Probe(present = setOf(listed))
        rule.setContent { ChunksTheme { ProtocolAbsentReceiptsCard(listOf(source(probe, setOf(listed))), owner) } }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_CHECK + "." + removed).performClick()
        rule.waitUntil(5_000) { owner.receipt(absentKey)?.phase == ProtocolOperationReceiptOwner.Phase.RECONCILED_ABSENT }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_RESULT + "." + removed)
            .assertTextContains("not registered", substring = true)
        assertEquals("exactly one read of the exact hash", listOf(removed), probe.reads)
        assertEquals("never a write", 0, probe.writes)
        assertEquals("the listed job's receipt stays for its own Details",
            ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(listedKey)?.phase)
    }

    @Test fun failedReadKeepsTheReceiptUnresolvedAndSaysSo() {
        val owner = ProtocolOperationReceiptOwner()
        val key = unknownRemove(owner, removed)
        val probe = Probe().apply { failRead = java.io.IOException("offline") }
        rule.setContent { ChunksTheme { ProtocolAbsentReceiptsCard(listOf(source(probe, emptySet())), owner) } }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_CHECK + "." + removed).performClick()
        rule.waitUntil(5_000) { probe.reads.size == 1 }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_RESULT + "." + removed)
            .assertTextContains("remains unresolved", substring = true)
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
        assertEquals(0, probe.writes)
    }

    @Test fun ownerChangeDuringTheReadOrAWrongHashReplyNeverReleases() = runBlocking {
        val owner = ProtocolOperationReceiptOwner()
        val key = unknownRemove(owner, removed)
        val probe = Probe()
        var current = true
        val ops = ClientOperations(probe, 3, 1)
        // The owner changes while the read is in flight.
        probe.failRead = null
        val changed = checkRetainedProtocolReceipt(owner, requireNotNull(owner.receipt(key)), ops) {
            (current).also { current = false }
        }
        assertEquals(ProtocolReceiptCheck.OWNER_CHANGED, changed)
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
        // A reply that names another job proves nothing about this receipt.
        probe.present = setOf(removed)
        probe.answerHash = listed
        assertEquals(ProtocolReceiptCheck.READ_FAILED,
            checkRetainedProtocolReceipt(owner, requireNotNull(owner.receipt(key)), ops) { true })
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
        assertEquals(0, probe.writes)
    }

    @Test fun cancellationPropagatesAndLeavesTheReceiptUnresolved() {
        val owner = ProtocolOperationReceiptOwner()
        val key = unknownRemove(owner, removed)
        val probe = Probe().apply { failRead = CancellationException("left the screen") }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                checkRetainedProtocolReceipt(owner, requireNotNull(owner.receipt(key)),
                    ClientOperations(probe, 3, 1)) { true }
            }
        }
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, owner.receipt(key)?.phase)
    }
}
