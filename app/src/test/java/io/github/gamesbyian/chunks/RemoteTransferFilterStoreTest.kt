package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.TransferFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation-only persistence; no connection or command may be triggered by these reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteTransferFilterStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val first = RememberedRemoteProfileRoster.identityOf("https://a.example/RPC2", "ian")
    private val second = RememberedRemoteProfileRoster.identityOf("https://a.example/RPC2", "another-account")

    @Test fun filtersSurviveStoreRecreationButStayIsolatedByExactAccount() {
        val store = RemoteTransferFilterStore(context)
        assertTrue(store.save(first, TransferFilter.STOPPED))
        assertTrue(store.save(second, TransferFilter.TRANSFERRING))
        assertEquals(TransferFilter.STOPPED, RemoteTransferFilterStore(context).load(first))
        assertEquals(TransferFilter.TRANSFERRING, RemoteTransferFilterStore(context).load(second))
        assertTrue(store.save(first, TransferFilter.ALL))
        assertEquals(TransferFilter.ALL, RemoteTransferFilterStore(context).load(first))
        assertEquals(TransferFilter.TRANSFERRING, RemoteTransferFilterStore(context).load(second))
    }

    @Test fun noUnknownOrPhoneIdentityMayCaptureARemoteView() {
        val store = RemoteTransferFilterStore(context)
        assertFalse(store.save(null, TransferFilter.STOPPED))
        assertFalse(store.save("", TransferFilter.STOPPED))
        assertFalse(store.save("phone", TransferFilter.STOPPED))
        assertFalse(store.save("https://a.example/RPC2", TransferFilter.STOPPED))
        assertEquals(TransferFilter.ALL, store.load(null))
        assertEquals(TransferFilter.ALL, store.load("phone"))
        assertEquals(TransferFilter.ALL, store.load("not-a-profile"))
    }

    @Test fun renamedOrDamagedSavedFilterFailsOpenToTheUnfilteredView() {
        assertEquals(TransferFilter.ALL, RemoteTransferFilterStore.decode(null))
        assertEquals(TransferFilter.ALL, RemoteTransferFilterStore.decode("GHOST"))
        assertEquals(TransferFilter.ALL, RemoteTransferFilterStore.decode("STOPPED;ACTIVE"))
        assertEquals(TransferFilter.ZERO_DOWNLOAD, RemoteTransferFilterStore.decode("ZERO_DOWNLOAD"))
    }

    @Test fun lastLoadedListCannotBeConflatedWithSearchResultOrLiveServerState() {
        assertEquals("No transfers match this search or filter.",
            RemoteEmptyTransfers.explanation(10, false, false, "main", true, false))
        assertEquals("No transfers in the last complete server list.",
            RemoteEmptyTransfers.explanation(0, false, false, "main", false, false))
        assertTrue(RemoteEmptyTransfers.explanation(0, false, false, "started", false, false)
            .contains("Other server views"))
        assertTrue(RemoteEmptyTransfers.explanation(0, true, false, "main", false, false)
            .startsWith("Checking"))
        assertTrue(RemoteEmptyTransfers.explanation(4, false, true, "main", true, true)
            .contains("out of date"))
        assertFalse(RemoteEmptyTransfers.canClear(" ", TransferFilter.ALL))
        assertTrue(RemoteEmptyTransfers.canClear("abc", TransferFilter.ALL))
        assertTrue(RemoteEmptyTransfers.canClear("", TransferFilter.STOPPED))
    }
}
