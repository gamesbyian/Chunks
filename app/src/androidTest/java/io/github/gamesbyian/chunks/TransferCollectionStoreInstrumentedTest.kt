package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TransferCollectionStoreInstrumentedTest {
    @Test fun explicitMembershipPersistsWithoutCrossingServerAccountOrTransfer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(ctx)
        val hash = "A".repeat(40)
        val secondHash = "B".repeat(40)
        val alice = "https://test.invalid/RPC2\u0000alice-collections-test"
        val bob = "https://test.invalid/RPC2\u0000bob-collections-test"
        val other = "https://other.invalid/RPC2\u0000alice-collections-test"
        try {
            assertTrue(store.add(alice, hash, "Star Trek"))
            assertTrue(store.add(alice, hash, "Science Fiction"))
            assertEquals(listOf("Science Fiction", "Star Trek"),
                TransferCollectionStore(ctx).forTransfer(alice, hash.lowercase()))
            assertEquals(emptyList<String>(), store.forTransfer(bob, hash))
            assertEquals(emptyList<String>(), store.forTransfer(other, hash))
            assertEquals(emptyList<String>(), store.forTransfer(alice, secondHash))
            assertTrue(store.remove(alice, hash, "star trek"))
            assertEquals(listOf("Science Fiction"), TransferCollectionStore(ctx).forTransfer(alice, hash))
            assertFalse(store.remove(alice, hash, "absent"))
        } finally {
            store.remove(alice, hash, "Star Trek")
            store.remove(alice, hash, "Science Fiction")
            store.remove(bob, hash, "Star Trek")
            store.remove(other, hash, "Star Trek")
            store.remove(alice, secondHash, "Star Trek")
        }
    }

    @Test fun independentStoresPreserveConcurrentMembershipsForOneExactTransfer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "https://test.invalid/RPC2\u0000parallel-membership-test"
        val hash = "9".repeat(40)
        val names = (1..8).map { "Parallel collection $it" }
        val store = TransferCollectionStore(ctx)
        val workers = Executors.newFixedThreadPool(names.size)
        val start = CyclicBarrier(names.size)
        try {
            names.forEach { store.remove(scope, hash, it) }
            val writes = names.map { name ->
                workers.submit<Boolean> {
                    val independent = TransferCollectionStore(ctx)
                    start.await(10, TimeUnit.SECONDS)
                    independent.add(scope, hash, name)
                }
            }
            writes.forEach { assertTrue(it.get(20, TimeUnit.SECONDS)) }
            assertEquals(names, store.forTransfer(scope, hash))
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(10, TimeUnit.SECONDS)
            names.forEach { store.remove(scope, hash, it) }
        }
    }
}
