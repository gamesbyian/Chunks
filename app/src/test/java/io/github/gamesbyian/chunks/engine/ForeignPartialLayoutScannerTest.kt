package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gamesbyian.chunks.engine.ForeignPartialLayoutScanner as S

class ForeignPartialLayoutScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, rel: String, size: Int = 3) {
        val f = File(root, rel); f.parentFile.mkdirs(); f.writeBytes(ByteArray(size))
    }

    @Test fun detectsSuffixCandidatesWithoutMutatingAnything() {
        val root = tmp.newFolder()
        file(root, "T/a.mkv.!qB", 10); file(root, "T/b.mkv.part", 20); file(root, "T/c.mkv.!ut", 5)
        file(root, "T/d.mkv.bc!", 7); file(root, "T/d.mkv.aria2", 1)
        val before = root.walkTopDown().map { it.path to it.length() }.toSet()
        val r = S.scan(root, listOf("T/a.mkv", "T/b.mkv", "T/c.mkv", "T/d.mkv", "T/e.mkv")) as S.Result.Found
        assertEquals(setOf(S.Kind.QBITTORRENT, S.Kind.TRANSMISSION, S.Kind.UTORRENT, S.Kind.BITCOMET, S.Kind.ARIA2_CONTROL),
            r.candidates.map { it.kind }.toSet())
        assertEquals(10L, r.candidates.first { it.kind == S.Kind.QBITTORRENT }.sizeBytes)
        assertTrue(r.ambiguous.isEmpty())
        assertEquals(before, root.walkTopDown().map { it.path to it.length() }.toSet())
    }

    @Test fun exactExistingFileIsNotScannedAndAuxiliaryIsNotImportable() {
        val root = tmp.newFolder()
        file(root, "a.bin"); file(root, "a.bin.part")
        file(root, "b.bin.aria2")
        val r = S.scan(root, listOf("a.bin", "b.bin")) as S.Result.Found
        assertEquals(listOf(S.Kind.ARIA2_CONTROL), r.candidates.map { it.kind })
        assertFalse(r.candidates.single().kind.importable)
        assertTrue(r.ambiguous.isEmpty())
    }

    @Test fun multipleImportableCandidatesAreAmbiguous() {
        val root = tmp.newFolder()
        file(root, "a.bin.!qB"); file(root, "a.bin.part")
        val r = S.scan(root, listOf("a.bin")) as S.Result.Found
        assertEquals(setOf("a.bin"), r.ambiguous)
    }

    @Test fun symlinksDirectoriesAndMissingParentsAreIgnoredOrRefused() {
        val root = tmp.newFolder(); val outside = tmp.newFile()
        Files.createSymbolicLink(File(root, "a.bin.part").toPath(), outside.toPath())
        File(root, "b.bin.!qB").mkdir()
        val r = S.scan(root, listOf("a.bin", "b.bin", "missing/c.bin")) as S.Result.Found
        assertTrue(r.candidates.isEmpty())
        val real = tmp.newFolder("real"); file(real, "x.bin.part")
        Files.createSymbolicLink(File(root, "link").toPath(), real.toPath())
        assertTrue(S.scan(root, listOf("link/x.bin")) is S.Result.Unknown)
    }

    @Test fun refusesUnsafeRootsAndPaths() {
        val root = tmp.newFolder()
        for (bad in listOf("../x", "/abs", "a//b", "a\\b", "./x", "")) {
            assertTrue(bad, S.scan(root, listOf(bad)) is S.Result.Unknown)
        }
        assertTrue(S.scan(File(root, "nope"), listOf("a")) is S.Result.Unknown)
        assertTrue(S.scan(root, emptyList()) is S.Result.Unknown)
        val link = File(tmp.root, "rootlink"); Files.createSymbolicLink(link.toPath(), root.toPath())
        assertTrue(S.scan(link, listOf("a")) is S.Result.Unknown)
    }

    @Test fun candidateListIsBounded() {
        val root = tmp.newFolder()
        val names = (0 until S.MAX_CANDIDATES + 5).map { "f$it.bin" }
        names.forEach { file(root, "$it.part", 1) }
        val r = S.scan(root, names) as S.Result.Found
        assertEquals(S.MAX_CANDIDATES, r.candidates.size); assertTrue(r.truncated)
    }
}
