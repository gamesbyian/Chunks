package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The verified remote Add reaches Combined only as its exact account's key. */
class RemoteAddedCombinedKeyTest {
    private val two = "https://box.example/rutorrent2/RPC2\u0000reader"
    private val three = "https://box.example/rutorrent3/RPC2\u0000reader"
    private val hash = "AB".repeat(20)
    private val record = RemoteAddedRecord(two, hash, RemoteAddedRecord.viewKey("", "ALL"))

    @Test fun currentAccountGetsItsExactLowercaseKey() {
        assertEquals(UnifiedTransferKey.of(EnvironmentSnapshots.serverIdOfAliasScope(two)!!, hash.lowercase()),
            record.combinedKey(two))
    }

    @Test fun anotherNumberedInstanceOrAccountNeverInheritsIt() {
        assertNull(record.combinedKey(three))
        assertNull(record.combinedKey("https://box.example/rutorrent2/RPC2\u0000other"))
        assertNull(record.combinedKey(""))
    }

    @Test fun malformedScopeOrHashGivesNoKey() {
        assertNull(RemoteAddedRecord("no-separator", hash, "").combinedKey("no-separator"))
        assertNull(RemoteAddedRecord(two, "not-a-hash", "").combinedKey(two))
    }

    @Test fun phoneAddIsKeyedToThePhoneOnly() {
        val key = phoneAddedKey(hash)!!
        assertEquals(EnvironmentSnapshots.PHONE, key.environment)
        assertEquals(hash.lowercase(), key.infoHash)
        assertNull(phoneAddedKey("not-a-hash"))
        // The same hash on a server is a different transfer identity.
        assert(key != record.combinedKey(two))
    }
}
