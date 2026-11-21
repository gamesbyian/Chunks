package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientJobTransferTest {
    private val hash = "ab".repeat(20)

    private class Fake(
        val name: String,
        var jobs: MutableSet<String> = mutableSetOf(),
        val caps: Set<ClientCapability> = ClientCapability.entries.toSet(),
        var export: Boolean = true
    ) : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        var adds = 0
        var removes = 0
        var removedData: Boolean? = null
        var onTorrentRead: () -> Unit = {}
        override suspend fun session() = ClientSessionInfo(kind, "1", "/d", caps)
        override suspend fun torrents() = jobs.map { t(it) }
        private fun t(h: String) = ClientTorrent(h, "n", ClientRunState.STOPPED, 1, 0, 0, 0, "/d")
        override suspend fun torrent(hash: String): ClientTorrent? {
            onTorrentRead()
            return if (hash in jobs) t(hash) else null
        }
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) { adds++; jobs += request.source.infoHash }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) { removes++; removedData = deleteData; jobs -= hash }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
        override suspend fun transferSource(hash: String): ClientAddSource? =
            if (export) ClientMagnets.build(hash, "n", listOf("udp://t.example:1/a")) else null
    }

    private fun transfer(a: Fake, b: Fake) =
        ClientJobTransfer(ClientOperations(a, 3, 1), a, ClientOperations(b, 3, 1), b,
            "source-" + a.name, "target-" + b.name)

    @Test fun verifiedCopyThenSessionOnlyRetirement() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b")
        val t = transfer(a, b)
        val copied = t.copy(hash, null, false) as ClientJobTransfer.CopyResult.Copied
        assertEquals(1, b.adds)
        assertTrue(t.retireSource(hash, copied) is ClientOperationOutcome.Verified)
        assertEquals(false, a.removedData)
    }

    @Test fun retireRefusedWhenTargetNoLongerHoldsTheJob() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b")
        val t = transfer(a, b)
        val copied = t.copy(hash, null, false) as ClientJobTransfer.CopyResult.Copied
        b.jobs.clear()
        assertTrue(t.retireSource(hash, copied) is ClientOperationOutcome.Refused)
        assertEquals(0, a.removes)
    }

    @Test fun changingConnectionDuringTheSourceReadKeepsTheJobAndConsumesConsent() = runBlocking {
        val source = Fake("source", mutableSetOf(hash))
        val target = Fake("target")
        val move = transfer(source, target)
        val copied = move.copy(hash, null, false) as ClientJobTransfer.CopyResult.Copied
        var authorized = true
        source.onTorrentRead = { authorized = false }

        assertTrue(move.retireSource(hash, copied) { authorized } is ClientOperationOutcome.Refused)
        source.onTorrentRead = {}
        authorized = true
        assertTrue(move.retireSource(hash, copied) { authorized } is ClientOperationOutcome.Refused)
        assertEquals(0, source.removes)
        assertEquals(setOf(hash), source.jobs)
        assertEquals(setOf(hash), target.jobs)
    }

    @Test fun absentSourceOrUnfaithfulExportWritesNothing() = runBlocking {
        val b = Fake("b")
        assertTrue(transfer(Fake("a"), b).copy(hash, null, false) is ClientJobTransfer.CopyResult.Refused)
        val noExport = Fake("a", mutableSetOf(hash)).apply { export = false }
        assertTrue(transfer(noExport, b).copy(hash, null, false) is ClientJobTransfer.CopyResult.Refused)
        val noCap = Fake("a", mutableSetOf(hash), caps = ClientCapability.entries.toSet() - ClientCapability.EXPORT_JOB)
        assertTrue(transfer(noCap, b).copy(hash, null, false) is ClientJobTransfer.CopyResult.Refused)
        assertEquals(0, b.adds)
    }

    @Test fun duplicateOnTargetIsNotCopiedAgain() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b", mutableSetOf(hash))
        val r = transfer(a, b).copy(hash, null, false)
        assertTrue(r is ClientJobTransfer.CopyResult.NotCopied)
        assertEquals(0, b.adds)
    }

    @Test fun cannotRetireWithoutOriginalVerifiedCopyOrAfterDuplicate() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b", mutableSetOf(hash))
        val t = transfer(a, b)
        // A pre-existing target registration is not a verified Move by this attempt.
        val forged = ClientJobTransfer.CopyResult.Copied(hash, false)
        assertTrue(t.retireSource(hash, forged) is ClientOperationOutcome.Refused)
        assertTrue(t.copy(hash, null, false) is ClientJobTransfer.CopyResult.NotCopied)
        assertTrue(t.retireSource(hash, forged) is ClientOperationOutcome.Refused)
        assertEquals(0, a.removes)
        assertEquals(0, b.adds)
    }

    @Test fun cannotReplayConsumedReceiptOrRetireDifferentHash() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b")
        val t = transfer(a, b)
        val copied = t.copy(hash, null, false) as ClientJobTransfer.CopyResult.Copied
        assertTrue(t.retireSource("cd".repeat(20), copied) is ClientOperationOutcome.Refused)
        assertEquals(0, a.removes)
        assertTrue(t.retireSource(hash, copied) is ClientOperationOutcome.Verified)
        assertTrue(t.retireSource(hash, copied) is ClientOperationOutcome.Refused)
        assertEquals(1, a.removes)
        assertEquals(false, a.removedData)
    }

    @Test fun secondFailedCopyInvalidatesFirstVerifiedReceipt() = runBlocking {
        val a = Fake("a", mutableSetOf(hash)); val b = Fake("b")
        val t = transfer(a, b)
        val copied = t.copy(hash, null, false) as ClientJobTransfer.CopyResult.Copied
        assertTrue(t.copy(hash, null, false) is ClientJobTransfer.CopyResult.NotCopied)
        assertTrue(t.retireSource(hash, copied) is ClientOperationOutcome.Refused)
        assertEquals(0, a.removes)
    }

    @Test fun sameClientOrWrongOperationOwnerCannotConstructMove() {
        val a = Fake("a"); val b = Fake("b")
        try {
            ClientJobTransfer(ClientOperations(a), a, ClientOperations(b), b,
                "same-client", "same-client")
            throw AssertionError("Same canonical client identity must be refused")
        } catch (_: IllegalArgumentException) { }
        try {
            ClientJobTransfer(ClientOperations(b), a, ClientOperations(b), b,
                "a", "b")
            throw AssertionError("Mismatched operation owner must be refused")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun builtMagnetKeepsExactHashAndOnlyWebTrackers() {
        val m = ClientMagnets.build(hash, "My name", listOf("https://t/a", "file:///etc/passwd", "udp://u:1"))
        assertEquals(hash, m.infoHash)
        assertTrue(!m.uri.contains("file%3A"))
        assertTrue(m.uri.contains("dn=My+name"))
    }
}
