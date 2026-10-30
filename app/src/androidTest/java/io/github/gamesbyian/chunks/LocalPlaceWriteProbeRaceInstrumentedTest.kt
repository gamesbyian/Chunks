package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Random
import java.nio.file.Files
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android exclusive-open/fstat/lstat identity and exclusive publication.
 * Controlled interleavings do not establish atomic pathname check/action safety.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaceWriteProbeRaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun destinationCreatedAfterFinalSurveyKeepsUnrelatedBytes() {
        val root = File(context.cacheDir, "probe-race-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val competing = File(root, ".chunks-write-probe-0000000000000000.ok")
            val bytes = byteArrayOf(3, 1, 4, 1, 5, 9)
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(root, predictable,
                beforePublish = { competing.writeBytes(bytes) })
            assertNotNull(refused)
            assertTrue(competing.isFile)
            assertArrayEquals(bytes, competing.readBytes())
            assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
        } finally {
            root.deleteRecursively()
        }

        // Same executed Android test count: separately prove that a symbolic
        // link planted after the LAST survey cannot be followed or removed.
        val linkRoot = File(context.cacheDir, "probe-link-after-survey-" + System.nanoTime())
        val unrelated = File(context.cacheDir, "probe-link-foreign-" + System.nanoTime())
        assertTrue(linkRoot.mkdirs())
        val outsideBytes = byteArrayOf(9, 7, 5, 3, 1)
        unrelated.writeBytes(outsideBytes)
        val link = File(linkRoot, ".chunks-write-probe-0000000000000000.ok").toPath()
        try {
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(linkRoot, predictable,
                beforePublish = { Files.createSymbolicLink(link, unrelated.toPath()) })
            assertNotNull("O_EXCL must reject a planted final-name symlink", refused)
            assertTrue(Files.isSymbolicLink(link))
            assertArrayEquals(outsideBytes, unrelated.readBytes())
            assertFalse(File(linkRoot, ".chunks-write-probe-0000000000000000.tmp").exists())
        } finally {
            // Both paths were created exclusively by this disposable fixture.
            if (Files.isSymbolicLink(link)) Files.deleteIfExists(link)
            linkRoot.deleteRecursively()
            unrelated.delete()
        }
    }

    @Test fun normalDescriptorBackedProbeReadsBackAndCleansItsOwnFile() {
        val root = File(context.cacheDir, "probe-normal-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val unrelated = File(root, "keep.bin")
            val bytes = byteArrayOf(1, 2, 3)
            unrelated.writeBytes(bytes)
            assertNull(LocalPlaceWriteProbe.verify(root))
            assertArrayEquals(bytes, unrelated.readBytes())
            assertTrue(root.list()!!.toList() == listOf("keep.bin"))
        } finally { root.deleteRecursively() }
    }

    @Test fun substitutionBeforeFstatCannotGrantOwnershipToForeignFile() {
        val root = File(context.cacheDir, "probe-before-fstat-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
            val displaced = File(root, "original-open-inode").toPath()
            val foreign = byteArrayOf(9, 8, 7, 6)
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(root, predictable, afterCreate = {
                Files.move(source, displaced)
                Files.write(source, foreign)
            })
            assertNotNull(refused)
            assertArrayEquals(foreign, Files.readAllBytes(source))
            assertTrue(Files.exists(displaced))
            assertFalse(File(root, ".chunks-write-probe-0000000000000000.ok").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun byteIdenticalReplacementIsStillForeignAndNeverCleanedUp() {
        val root = File(context.cacheDir, "probe-identical-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
            val displaced = File(root, "original-inode").toPath()
            var foreignBytes: ByteArray? = null
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(root, predictable, beforeRename = {
                foreignBytes = Files.readAllBytes(source)
                Files.move(source, displaced)
                Files.copy(displaced, source)
            })
            assertNotNull(refused)
            assertArrayEquals(foreignBytes!!, Files.readAllBytes(source))
            assertArrayEquals(foreignBytes!!, Files.readAllBytes(displaced))
            assertFalse(File(root, ".chunks-write-probe-0000000000000000.ok").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkReplacementAtRenameBoundaryPreservesLinkAndTarget() {
        val root = File(context.cacheDir, "probe-link-" + System.nanoTime())
        assertTrue(root.mkdirs())
        try {
            val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
            val displaced = File(root, "original-inode").toPath()
            val foreign = File(root, "foreign.bin")
            val bytes = byteArrayOf(2, 4, 6, 8)
            foreign.writeBytes(bytes)
            val predictable = object : Random() {
                override fun nextBytes(out: ByteArray) { out.fill(0) }
            }
            val refused = LocalPlaceWriteProbe.verify(root, predictable, beforeRename = {
                Files.move(source, displaced)
                Files.createSymbolicLink(source, foreign.toPath())
            })
            assertNotNull(refused)
            assertTrue(Files.isSymbolicLink(source))
            assertArrayEquals(bytes, foreign.readBytes())
            assertTrue(Files.exists(displaced))
        } finally { root.deleteRecursively() }
    }

}
