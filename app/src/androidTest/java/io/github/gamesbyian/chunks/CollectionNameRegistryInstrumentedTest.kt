package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionNameRegistryInstrumentedTest {
    @Test fun concurrentIndependentInstancesDoNotLoseExplicitSavedNames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "https://test.invalid/RPC2\\u0000registry-concurrency-test"
        val names = (0 until 18).map { "Concurrent collection $it" }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val begin = java.util.concurrent.CountDownLatch(1)
        try {
            names.forEach { CollectionNameRegistry(ctx).forget(scope, it) }
            val results = names.map { name ->
                pool.submit<Boolean> {
                    begin.await()
                    CollectionNameRegistry(ctx).create(scope, name)
                }
            }
            begin.countDown()
            assertTrue(results.all { it.get(20, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(names.toSet(), CollectionNameRegistry(ctx).names(scope).toSet())
        } finally {
            begin.countDown()
            pool.shutdownNow()
            names.forEach { CollectionNameRegistry(ctx).forget(scope, it) }
        }
    }

    @Test fun emptyCollectionPersistsAcrossInstancesWithoutCrossingAccountsOrLocalEngine() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CollectionNameRegistry(ctx)
        val alice = "https://test.invalid/RPC2\u0000alice-registry-case"
        val bob = "https://test.invalid/RPC2\u0000bob-registry-case"
        val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val name = "Independent project collection"
        // Use unique explicit names to avoid mutating unrelated test metadata.
        try {
            assertTrue(store.create(alice, name))
            assertEquals(listOf(name), CollectionNameRegistry(ctx).names(alice)
                .filter { it == name })
            assertFalse(store.names(bob).contains(name))
            assertFalse(store.names(local).contains(name))
        } finally {
            store.forget(alice, name)
        }
    }
}
