package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android NIO probe semantics with a controlled destination creation
 * after the last existence survey, not an adversarial scheduling guarantee.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaceWriteProbeRaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun destinationCreatedAtRenameBoundaryKeepsUnrelatedBytes() {
        val root = File(context.cacheDir, "probe-race-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val competing = File(root, ".chunks-write-probe-0000000000000000.ok")
            val bytes = byteArrayOf(3, 1, 4, 1, 5, 9)
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(root, predictable) {
                competing.writeBytes(bytes)
            }
            assertNotNull(refused)
            assertTrue(competing.isFile)
            assertArrayEquals(bytes, competing.readBytes())
            assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
