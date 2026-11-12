package io.github.gamesbyian.chunks.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gamesbyian.chunks.engine.ForeignPartialLayoutScanner as S
import io.github.gamesbyian.chunks.engine.ForeignPartialRebind as R

class ForeignPartialRebindTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun put(root: File, rel: String, text: String) { File(root, rel).also { it.parentFile.mkdirs() }.writeText(text) }
    private fun found(root: File, vararg declared: String) =
        (S.scan(root, declared.toList()) as S.Result.Found).candidates

    @Test fun adoptRenamesPreservingBytesAndUndoRestores() {
        val root = tmp.newFolder(); put(root, "T/a.mkv.!qB", "AAA"); put(root, "T/b.mkv.part", "BB")
        val c = found(root, "T/a.mkv", "T/b.mkv")
        val r = R.adopt(root, c) as R.Result.Adopted
        assertEquals("AAA", File(root, "T/a.mkv").readText()); assertEquals("BB", File(root, "T/b.mkv").readText())
        assertFalse(File(root, "T/a.mkv.!qB").exists())
        assertTrue(R.undo(root, r.receipt))
        assertEquals("AAA", File(root, "T/a.mkv.!qB").readText()); assertFalse(File(root, "T/a.mkv").exists())
    }

    @Test fun refusesAuxiliaryAmbiguousAndStaleCandidatesWithoutChanges() {
        val root = tmp.newFolder(); put(root, "a.bin.aria2", "x")
        assertTrue(R.adopt(root, found(root, "a.bin")) is R.Result.Refused)
        put(root, "b.bin.!qB", "1"); put(root, "b.bin.part", "2")
        val both = found(root, "b.bin").filter { it.kind == S.Kind.QBITTORRENT }
        assertTrue(R.adopt(root, both) is R.Result.Refused)
        assertTrue(File(root, "b.bin.!qB").exists() && File(root, "b.bin.part").exists())
        val root2 = tmp.newFolder(); put(root2, "c.bin.part", "12")
        val stale = found(root2, "c.bin"); put(root2, "c.bin.part", "1234")
        assertTrue(R.adopt(root2, stale) is R.Result.Refused)
        assertEquals("1234", File(root2, "c.bin.part").readText())
    }

    @Test fun midwayFailureRollsBackAndNeverReplaces() {
        val root = tmp.newFolder(); put(root, "a.bin.part", "A"); put(root, "z.bin.part", "Z")
        val c = found(root, "a.bin", "z.bin")
        // Make the second rename fail by occupying its destination after the scan.
        val r = R.adopt(root, c.filter { it.declaredPath == "a.bin" } + c.filter { it.declaredPath == "z.bin" })
        assertTrue(r is R.Result.Adopted)
        val root2 = tmp.newFolder(); put(root2, "a.bin.part", "A"); put(root2, "z.bin.part", "Z")
        val c2 = found(root2, "a.bin", "z.bin")
        put(root2, "z.bin", "EXISTING")
        val bad = R.adopt(root2, c2)
        assertTrue(bad is R.Result.Refused)
        assertEquals("EXISTING", File(root2, "z.bin").readText())
        assertEquals("A", File(root2, "a.bin.part").readText()); assertFalse(File(root2, "a.bin").exists())
    }

    @Test fun undoRefusesWhenOriginalNameIsOccupied() {
        val root = tmp.newFolder(); put(root, "a.bin.part", "A")
        val r = R.adopt(root, found(root, "a.bin")) as R.Result.Adopted
        put(root, "a.bin.part", "NEW")
        assertFalse(R.undo(root, r.receipt))
        assertEquals("NEW", File(root, "a.bin.part").readText()); assertEquals("A", File(root, "a.bin").readText())
    }
}
