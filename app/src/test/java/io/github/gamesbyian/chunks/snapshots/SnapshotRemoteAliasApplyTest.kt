package io.github.gamesbyian.chunks.snapshots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.TransferAliasStore
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SnapshotRemoteAliasApplyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("chunks_display_aliases_v1", Context.MODE_PRIVATE)
    private val hash = "ab".repeat(20)
    private val alice = "https://seed.example/RPC2\u0000alice"
    private val bob = "https://seed.example/RPC2\u0000bob"
    private val remote = RemoteTransfer(hash, "Original", 9, 0, false, "/safe")
    private val entry = SnapshotRestorePreviewV1.Entry(
        hash, "Original", SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
        selectedForReview = false, historicalDirectoryHint = "/unsafe",
        displayAlias = "Saved title")
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }

    @Test fun saveOnlyTheExactAccountThenConfirmReadback() {
        val store = TransferAliasStore(context)
        assertEquals("Saved remote display alias verified on this device.",
            applyRemoteSnapshotAlias(store, alice, entry, remote))
        assertEquals("Saved title", store.get(alice, hash))
        assertNull(store.get(bob, hash))
        assertEquals("This account already uses the saved alias.",
            applyRemoteSnapshotAlias(store, alice, entry, remote))
        assertEquals(1, prefs.all.size)
    }
    @Test fun existingManualAliasOnEitherPrincipalAlwaysWins() {
        val store = TransferAliasStore(context)
        assertTrue(store.save(bob, hash, "Bob manual"))
        assertTrue(store.save(alice, hash, "Alice manual"))
        assertTrue(applyRemoteSnapshotAlias(store, bob, entry, remote)
            .contains("existing alias was kept"))
        assertEquals("Bob manual", store.get(bob, hash))
        assertEquals("Alice manual", store.get(alice, hash))
    }
    @Test fun refuseUnknownAccountOrMismatchedOriginalWithoutWriting() {
        val store = TransferAliasStore(context)
        val cases = listOf(
            Triple("", entry, remote), Triple(alice + "\u0000other", entry, remote),
            Triple(alice, entry, remote.copy(hash = "cd".repeat(20))),
            Triple(alice, entry, remote.copy(name = "Different")),
            Triple(alice, entry, null),
            Triple(alice, entry.copy(displayAlias = null), remote),
            Triple(alice, entry.copy(displayAlias = "Original"), remote),
            Triple(alice, entry.copy(decision =
                SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW), remote))
        cases.forEach { (scope, snapshot, actual) ->
            assertTrue(applyRemoteSnapshotAlias(store, scope, snapshot, actual)
                .contains("No alias was applied"))
        }
        assertTrue(prefs.all.isEmpty())
    }
}
