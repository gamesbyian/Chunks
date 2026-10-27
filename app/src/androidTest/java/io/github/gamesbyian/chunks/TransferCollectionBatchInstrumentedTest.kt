package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferCollectionBatchInstrumentedTest {
    private val store get() = TransferCollectionStore(
        InstrumentationRegistry.getInstrumentation().targetContext)
    private val scope = "https://test.invalid/RPC2\u0000chunks-batch-regression"
    private val first = "1".repeat(40)
    private val second = "2".repeat(40)
    private val third = "3".repeat(40)

    private fun clean() {
        for (hash in listOf(first, second, third)) {
            for (name in listOf("Star Trek", "Sci-fi") + (1..32).map { "cap-$it" })
                store.remove(scope, hash, name)
        }
    }

    @Test fun durableBatchWritesOnlySelectedScopedHashesAndKeepsOtherAccounts() {
        clean()
        val otherScope = "https://test.invalid/RPC2\u0000another-user-batch"
        try {
            assertTrue(store.addBatch(scope, listOf(first, second), "Star Trek"))
            assertEquals(listOf("Star Trek"), store.forTransfer(scope, first))
            assertEquals(listOf("Star Trek"), store.forTransfer(scope, second))
            assertTrue(store.forTransfer(scope, third).isEmpty())
            assertTrue(store.forTransfer(otherScope, first).isEmpty())
            assertTrue(store.addBatch(scope, listOf(first, second), "star trek"))
            assertEquals(1, TransferCollectionStore(
                InstrumentationRegistry.getInstrumentation().targetContext
            ).forTransfer(scope, second).size)
        } finally {
            clean()
        }
    }

    @Test fun fullMembershipOnOneTransferBlocksAllBatchWrites() {
        clean()
        try {
            (1..32).forEach { assertTrue(store.add(scope, second, "cap-$it")) }
            assertFalse(store.addBatch(scope, listOf(first, second), "Sci-fi"))
            assertFalse(store.forTransfer(scope, first).contains("Sci-fi"))
            assertFalse(store.forTransfer(scope, second).contains("Sci-fi"))
            assertEquals(32, store.forTransfer(scope, second).size)
        } finally {
            clean()
        }
    }

    @Test fun duplicateOrMalformedHashesOrNamesCannotProducePartialWrites() {
        clean()
        try {
            assertFalse(store.addBatch(scope, listOf(first, first.lowercase()), "Sci-fi"))
            assertFalse(store.addBatch(scope, listOf(first, "bad hash"), "Sci-fi"))
            assertFalse(store.addBatch(scope, listOf(first, second), "bad\nlabel"))
            assertFalse(store.addBatch(scope, emptyList(), "Sci-fi"))
            assertFalse(store.addBatch(scope, List(513) { first }, "Sci-fi"))
            assertTrue(store.forTransfer(scope, first).isEmpty())
            assertTrue(store.forTransfer(scope, second).isEmpty())
        } finally {
            clean()
        }
    }
}
