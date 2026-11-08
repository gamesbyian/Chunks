package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Server aliases use endpoint + exact authenticated username, not a trimmed principal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferAliasStoreExactPrincipalTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("chunks_display_aliases_v1", Context.MODE_PRIVATE)
    private val endpoint = "https://seed.example/RPC2"
    private val hash = "ab".repeat(20)
    private val alice = endpoint + "\u0000alice"
    private val aliceSpace = endpoint + "\u0000alice "
    private val emptyUser = endpoint + "\u0000"
    private val blankUser = endpoint + "\u0000 "

    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }

    @Test fun trailingSpaceIsPartOfPrincipalIdentityForSameTorrentAndEndpoint() {
        val store = TransferAliasStore(context)
        assertTrue(store.save(alice, hash, "Account Alice"))
        assertNull(store.get(aliceSpace, hash))
        assertTrue(store.save(aliceSpace, hash, "Account Alice Space"))
        assertEquals("Account Alice", store.get(alice, hash))
        assertEquals("Account Alice Space", store.get(aliceSpace, hash))
        assertEquals(2, prefs.all.size)
        // A change on one account must never erase another account's alias.
        assertTrue(store.save(alice, hash, null))
        assertNull(store.get(alice, hash))
        assertEquals("Account Alice Space", store.get(aliceSpace, hash))
    }

    @Test fun emptyUsernameAndSingleWhitespaceUsernameNeverShareAlias() {
        val store = TransferAliasStore(context)
        assertTrue(store.save(emptyUser, hash, "Empty user"))
        assertNull(store.get(blankUser, hash))
        assertTrue(store.save(blankUser, hash, "Space user"))
        assertEquals("Empty user", store.get(emptyUser, hash))
        assertEquals("Space user", store.get(blankUser, hash))
    }

    @Test fun combinedBatchProjectionUsesSameExactPrincipalKeys() {
        val store = TransferAliasStore(context)
        assertTrue(store.save(alice, hash, "Exact account"))
        assertTrue(store.save(aliceSpace, hash, "Whitespace account"))
        val a = CombinedAliasRequest(UnifiedTransferKey.of(
            EnvironmentSnapshots.serverId(endpoint, "alice"), hash), alice)
        val b = CombinedAliasRequest(UnifiedTransferKey.of(
            EnvironmentSnapshots.serverId(endpoint, "alice "), hash), aliceSpace)
        assertEquals(mapOf(a to "Exact account", b to "Whitespace account"),
            store.getBatch(setOf(a, b)))
    }

    @Test fun malformedMultiSeparatorAccountScopeIsRejectedWithoutAWrite() {
        val store = TransferAliasStore(context)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(endpoint + "\u0000alice\u0000bob", hash, "Ambiguous")
        }
        assertTrue(prefs.all.isEmpty())
    }
}
