package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferAliasStoreInstrumentedTest {
    @Test fun bulkLookupReadsOnlyExactAccountScopeAndTracksSuccessfulWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferAliasStore(context)
        val endpoint = "https://batch-identity.example.test/RPC2"
        val alice = "$endpoint\u0000alice"
        val bob = "$endpoint\u0000bob"
        val hash = "9".repeat(40)
        val aliceKey = io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(endpoint, "alice"), hash)
        val bobKey = io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverId(endpoint, "bob"), hash)
        val aliceRequest = CombinedAliasRequest(aliceKey, alice)
        val bobRequest = CombinedAliasRequest(bobKey, bob)
        try {
            val before = TransferAliasStore.revision
            assertTrue(store.save(alice, hash, "Alice private label"))
            assertTrue(TransferAliasStore.revision > before)
            assertEquals(mapOf(aliceRequest to "Alice private label"),
                store.getBatch(setOf(aliceRequest, bobRequest)))
            assertTrue(store.save(bob, hash, "Bob separate label"))
            assertEquals(mapOf(aliceRequest to "Alice private label",
                bobRequest to "Bob separate label"), store.getBatch(setOf(aliceRequest, bobRequest)))
            assertTrue(store.save(alice, hash, null))
            assertEquals(mapOf(bobRequest to "Bob separate label"),
                store.getBatch(setOf(aliceRequest, bobRequest)))
        } finally {
            store.save(alice, hash, null)
            store.save(bob, hash, null)
        }
    }

    @Test fun aliasesAreLocalAndScopedToServerAndInfoHash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferAliasStore(context)
        val hash = "A".repeat(40)
        val otherHash = "B".repeat(40)
        val server = "https://display-names.example.test/RPC2\u0000alice"
        val otherServer = "https://different.example.test/RPC2\u0000alice"
        val otherAccount = "https://display-names.example.test/RPC2\u0000bob"
        try {
            assertTrue(store.save(server, hash, "Celebrity Jeopardy S04E02"))
            assertEquals("Celebrity Jeopardy S04E02", store.get(server, hash))
            assertNull(store.get(otherServer, hash))
            assertNull(store.get(otherAccount, hash))
            assertNull(store.get(server, otherHash))
            // Case-insensitive infohash identity within the same server.
            assertEquals("Celebrity Jeopardy S04E02", store.get(server, hash.lowercase()))
            assertTrue(store.save(server, hash, null))
            assertNull(store.get(server, hash))
        } finally {
            store.save(server, hash, null)
            store.save(otherServer, hash, null)
            store.save(server, otherHash, null)
        }
    }
}
