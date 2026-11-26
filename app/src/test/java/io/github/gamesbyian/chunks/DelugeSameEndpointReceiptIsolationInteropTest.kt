package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendIdentity
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientOperationWriteBoundary
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.DelugeClient
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #2026, same endpoint: two real Deluge daemons behind ONE Web UI are two
 * exact Chunks identities (backend + endpoint + principal + daemon host).
 * The same infohash on both must keep separate retained receipts: a Remove
 * with data on daemon A leaves A's receipt unresolved without blocking B's
 * same-hash job, and A's read-only check releases only A's receipt.
 * Drives the receipt owner exactly as the operations panel does. Each daemon
 * gets its own explicit download folder, proven distinct before any data
 * deletion, so A's delete can never reach B's payload. Fixture-only jobs,
 * removed in `finally`. Env-gated (CHUNKS_INTEROP_DELUGE2_*).
 */
class DelugeSameEndpointReceiptIsolationInteropTest {
    private fun env(name: String) = System.getenv("CHUNKS_INTEROP_DELUGE2_$name").orEmpty()

    @Test fun sameHashOnTwoDaemonsOfOneWebUiKeepsSeparateReceipts(): Unit = runBlocking {
        assumeTrue(env("URL").isNotBlank() && env("HOST_A").isNotBlank() && env("HOST_B").isNotBlank())
        val payloadRoot = System.getenv("CHUNKS_INTEROP_DELUGE_PAYLOAD_DIR").orEmpty()
        assumeTrue(payloadRoot.isNotBlank())
        val credentials = RemoteCredentials(env("URL"), "", env("PASS"))
        val hostA = env("HOST_A")
        val hostB = env("HOST_B")
        // The app creates fresh operations per command; the shared Web UI slot
        // is re-bound to the exact daemon by each new client.
        fun ops(host: String) = ClientOperations(DelugeClient(credentials, host), 40, 250)
        val idA = RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, credentials.endpoint, "", hostA).value
        val idB = RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, credentials.endpoint, "", hostB).value
        assertNotEquals("two daemons behind one endpoint are two identities", idA, idB)
        // start_local_clients.sh creates both; each daemon writes only its own.
        val directoryA = File(payloadRoot).absolutePath
        val directoryB = File(payloadRoot).resolveSibling("de2").absolutePath
        assertNotEquals(directoryA, directoryB)

        val random = SecureRandom()
        val name = "chunks-same-endpoint-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
        val payload = ByteArray(48 * 1024).also(random::nextBytes)
        val fixture = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf(name), payload.size.toLong()) { payload.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
        val hash = fixture.infoHashV1
        try {
            for ((host, directory) in listOf(hostA to directoryA, hostB to directoryB)) {
                val added = ops(host).add(ClientAddRequest(ClientAddSource.TorrentFile(fixture.torrent), directory,
                    startNow = false))
                assertTrue("fixture added stopped on $host: $added", added is ClientOperationOutcome.Verified)
            }
            val observedA = ops(hostA).client.torrent(hash)?.downloadDirectory
            val observedB = ops(hostB).client.torrent(hash)?.downloadDirectory
            assertEquals(directoryA, observedA)
            assertEquals(directoryB, observedB)
            assertNotEquals("A's data delete can never reach B's payload", observedA, observedB)
            println("CHUNKS_SAME_ENDPOINT_RECEIPT: same_hash_on_two_daemons=true distinct_payload_folders=true")

            val owner = ProtocolOperationReceiptOwner()
            val keyA = ProtocolOperationReceiptOwner.Key(idA, hash)
            val keyB = ProtocolOperationReceiptOwner.Key(idB, hash)
            suspend fun command(key: ProtocolOperationReceiptOwner.Key, kind: ProtocolOperationKind,
                                block: suspend () -> ClientOperationOutcome): ClientOperationOutcome {
                val ticket = requireNotNull(owner.begin(key, "g", kind)) { "$kind on ${key.profileId} was blocked" }
                try {
                    val outcome = withContext(ClientOperationWriteBoundary {
                        check(owner.possibleWrite(ticket)) { "receipt refused the write" }
                    }) { block() }
                    owner.complete(ticket, outcome)
                    return outcome
                } finally {
                    owner.cancel(ticket)
                }
            }

            // Remove with data on daemon A: registration absent, deletion unverified.
            command(keyA, ProtocolOperationKind.REMOVE_DATA) {
                ops(hostA).removeReviewedPayload(hash, requireNotNull(observedA))
            }
            assertEquals(ProtocolOperationReceiptOwner.Phase.REGISTRATION_ABSENT, owner.receipt(keyA)?.phase)
            assertNull("independently absent on A", ops(hostA).client.torrent(hash))
            assertEquals("B still points at its own folder", directoryB, ops(hostB).client.torrent(hash)?.downloadDirectory)
            println("CHUNKS_SAME_ENDPOINT_RECEIPT: a_removed_receipt_retained=true")

            // The same hash on daemon B is neither blocked nor checkable through A.
            assertTrue(owner.checkable(idB).isEmpty())
            val started = command(keyB, ProtocolOperationKind.START) { ops(hostB).setStarted(hash, true) }
            assertTrue("B starts despite A's unresolved receipt: $started", started is ClientOperationOutcome.Verified)
            assertNull("a verified command leaves no receipt on B", owner.receipt(keyB))
            assertEquals(true, ops(hostB).client.torrent(hash)?.runState?.started)
            assertEquals("A's receipt is untouched by B", ProtocolOperationReceiptOwner.Phase.REGISTRATION_ABSENT,
                owner.receipt(keyA)?.phase)
            println("CHUNKS_SAME_ENDPOINT_RECEIPT: b_same_hash_commandable=true")

            // A's read-only check releases A's receipt only, with no write to either daemon.
            val beforeB = ops(hostB).client.torrent(hash)
            val receiptA = owner.checkable(idA).single()
            assertEquals(ProtocolReceiptCheck.RELEASED,
                checkRetainedProtocolReceipt(owner, receiptA, ops(hostA)) { true })
            assertEquals(ProtocolOperationReceiptOwner.Phase.RECONCILED_ABSENT, owner.receipt(keyA)?.phase)
            assertEquals("the check did not touch B", beforeB?.runState, ops(hostB).client.torrent(hash)?.runState)
            println("CHUNKS_SAME_ENDPOINT_RECEIPT: a_check_released_only_a=true")
        } finally {
            // Exact fixture hash only, job-only removal, on each daemon that still has it.
            for (host in listOf(hostA, hostB)) {
                runCatching {
                    if (ops(host).client.torrent(hash) != null) {
                        ops(host).setStarted(hash, false)
                        ops(host).remove(hash, deleteData = false)
                    }
                }
            }
        }
        assertNull(ops(hostA).client.torrent(hash))
        assertNull(ops(hostB).client.torrent(hash))
    }
}
