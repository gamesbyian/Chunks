package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.model.StorageWarning
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCapPreferenceStoreInstrumentedTest {
    @Test fun persistsAdvisoryCapPerFolderAndDeduplicatesThresholds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rootA = File(context.filesDir, "cap-test-a").apply { mkdirs() }
        val rootB = File(context.filesDir, "cap-test-b").apply { mkdirs() }
        val store = LocalCapPreferenceStore(context)
        try {
            store.set(rootA, null)
            store.set(rootB, null)
            assertTrue(store.set(rootA, 1_000L))
            assertEquals(1_000L, LocalCapPreferenceStore(context).get(rootA))
            assertNull(store.get(rootB))
            assertEquals(StorageWarning.SEVENTY, store.observe(rootA, 1_000, 700, null))
            assertNull(store.observe(rootA, 1_000, 710, null))
            assertEquals(StorageWarning.NINETY_FIVE, store.observe(rootA, 1_000, 950, null))
            assertNull(LocalCapPreferenceStore(context).observe(rootA, 1_000, 960, null))
            assertNull(store.get(rootB))
            assertTrue(store.set(rootA, null))
            assertNull(store.get(rootA))
        } finally {
            store.set(rootA, null)
            store.set(rootB, null)
            rootA.deleteRecursively()
            rootB.deleteRecursively()
        }
    }

    @Test fun missingRootCannotCreateOrReadAnArbitraryCap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "missing-cap-root-" + System.nanoTime())
        val store = LocalCapPreferenceStore(context)
        assertNull(store.get(root))
        assertFalse(store.set(root, 1_000L))
        assertNull(store.observe(root, 1_000, 800, null))
    }
}
