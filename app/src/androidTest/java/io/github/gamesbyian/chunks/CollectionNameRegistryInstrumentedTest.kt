package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionNameRegistryInstrumentedTest {
    @Test fun emptyCollectionPersistsAcrossInstancesWithoutCrossingAccountsOrLocalEngine() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CollectionNameRegistry(ctx)
        val alice = "https://test.invalid/RPC2\u0000alice-registry-case"
        val bob = "https://test.invalid/RPC2\u0000bob-registry-case"
        val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val name = "Independent project collection"
        // Use unique explicit names to avoid mutating unrelated test metadata.
        assertTrue(store.create(alice, name))
        assertEquals(listOf(name), CollectionNameRegistry(ctx).names(alice)
            .filter { it == name })
        assertFalse(store.names(bob).contains(name))
        assertFalse(store.names(local).contains(name))
    }
}
