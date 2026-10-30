package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCheckedPayloadReservationsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun twoStoppedJobsCannotReserveTheSameStillAbsentPayload() {
        val root = temp.newFolder("shared")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("hash-a", root, listOf("series/episode.mkv")))
        assertTrue("A clean directory must not authorize a second writer",
            index.reserve("hash-b", root, listOf("series/episode.mkv"))
                is LocalCheckedPayloadReservations.Result.Refused)
        assertFalse(File(root, "series/episode.mkv").exists())
        // A paused or stopped torrent retains its reservation until Remove.
        assertTrue(index.reserve("hash-b", root, listOf("series/episode.mkv"))
            is LocalCheckedPayloadReservations.Result.Refused)
        index.release("hash-a")
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("hash-b", root, listOf("series/episode.mkv")))
    }

    @Test fun oneConflictingFileRejectsAnEntireMultifileAdmission() {
        val root = temp.newFolder("multi")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("a", root, listOf("same/one.bin", "other/two.bin")))
        assertTrue(index.reserve("b", root, listOf("unique/three.bin", "same/one.bin"))
            is LocalCheckedPayloadReservations.Result.Refused)
        // Refusing the batch must not strand its first, nonconflicting name.
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("c", root, listOf("unique/three.bin")))
    }

    @Test fun nestedFilesAndParentNamesConflictWithoutPrefixFalsePositives() {
        val root = temp.newFolder("nested")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("a", root, listOf("season/file/part.bin")))
        assertTrue(index.reserve("b", root, listOf("season/file"))
            is LocalCheckedPayloadReservations.Result.Refused)
        assertTrue(index.reserve("c", root, listOf("season/file/part.bin/child"))
            is LocalCheckedPayloadReservations.Result.Refused)
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("d", root, listOf("season/file-other")))
    }

    @Test fun ancestorAliasesCaseFoldingAndUnicodeAliasesAreConservative() {
        val root = temp.newFolder("real")
        val parent = temp.newFolder("outer")
        Files.createSymbolicLink(File(parent, "alias").toPath(), root.parentFile.toPath())
        val alias = File(parent, "alias/real")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("a", root, listOf("Caf\u00e9/EPISODE.MKV")))
        assertTrue(index.reserve("b", alias, listOf("cafe\u0301/episode.mkv"))
            is LocalCheckedPayloadReservations.Result.Refused)
        // A symlink as the root itself cannot be used to silently reserve it.
        val directLink = File(parent, "direct")
        Files.createSymbolicLink(directLink.toPath(), root.toPath())
        assertTrue(index.reserve("c", directLink, listOf("other.mkv"))
            is LocalCheckedPayloadReservations.Result.Refused)
    }

    @Test fun independentPhysicalRootsDoNotShareReservations() {
        val first = temp.newFolder("first")
        val second = temp.newFolder("second")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("a", first, listOf("same.mkv")))
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("b", second, listOf("same.mkv")))
    }

    @Test fun failClosedInputsDoNotLeaveOrphanedClaims() {
        val root = temp.newFolder("inputs")
        val index = LocalCheckedPayloadReservations()
        for (bad in listOf("../escape", "a/../b", "a//b", "/outside", "a\\b")) {
            assertTrue(bad, index.reserve("invalid", root, listOf("valid", bad))
                is LocalCheckedPayloadReservations.Result.Refused)
        }
        assertTrue(index.reserve("invalid", File(root, "missing"), listOf("valid"))
            is LocalCheckedPayloadReservations.Result.Refused)
        assertTrue(index.reserve("invalid", root, emptyList())
            is LocalCheckedPayloadReservations.Result.Refused)
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("valid", root, listOf("valid")))
        assertTrue(index.reserve("valid", root, listOf("elsewhere"))
            is LocalCheckedPayloadReservations.Result.Refused)
        index.clear()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("valid", root, listOf("elsewhere")))
    }

    @Test fun metadataMagnetCanRevalidateOnlyItsOriginalPhysicalPathSet() {
        val root = temp.newFolder("magnet-stable")
        val other = temp.newFolder("magnet-swapped")
        val index = LocalCheckedPayloadReservations()
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("magnet", root, listOf("S01/episode.bin")))
        assertEquals(LocalCheckedPayloadReservations.Result.Reserved,
            index.reserve("magnet", root, listOf("S01/episode.bin"),
                allowExistingOwner = true))
        assertTrue("Retargeted native save root must not reuse a stale reservation",
            index.reserve("magnet", other, listOf("S01/episode.bin"),
                allowExistingOwner = true) is LocalCheckedPayloadReservations.Result.Refused)
        assertTrue("Retargeted native relative file must not reuse a stale reservation",
            index.reserve("magnet", root, listOf("S02/episode.bin"),
                allowExistingOwner = true) is LocalCheckedPayloadReservations.Result.Refused)
        assertTrue("A same-hash new Add after Remove must not reuse a native tombstone",
            index.reserve("magnet", root, listOf("S01/episode.bin"))
                is LocalCheckedPayloadReservations.Result.Refused)
    }

    @Test fun concurrentAdmissionsCannotBothClaimOneStillAbsentPath() {
        val root = temp.newFolder("parallel")
        val index = LocalCheckedPayloadReservations()
        val workers = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val futures = listOf("a", "b").map { owner ->
                workers.submit<LocalCheckedPayloadReservations.Result> {
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    index.reserve(owner, root, listOf("same/file.bin"))
                }
            }
            go.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == LocalCheckedPayloadReservations.Result.Reserved })
            assertEquals(1, results.count { it is LocalCheckedPayloadReservations.Result.Refused })
        } finally { workers.shutdownNow() }
    }
}
