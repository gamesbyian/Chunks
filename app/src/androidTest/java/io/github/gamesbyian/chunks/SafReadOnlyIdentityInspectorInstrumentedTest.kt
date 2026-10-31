package io.github.gamesbyian.chunks

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.storage.SafCreationCandidate
import io.github.gamesbyian.chunks.storage.SafReadOnlyIdentityInspector
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SAF ContentResolver fixture only; no claim of vendor-provider atomicity. */
@RunWith(AndroidJUnit4::class)
class SafReadOnlyIdentityInspectorInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)
    private val tree get() = FixtureDocumentsProvider.treeUri(context)
    private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))

    @Before fun setup() { base.deleteRecursively(); base.mkdirs() }
    @After fun cleanup() { base.deleteRecursively() }

    @Test fun existingUriReturnedAsCreateResultIsRejectedWithoutOpeningIt() {
        val foreign = File(base, "foreign.bin")
        foreign.writeBytes("FOREIGN-BYTES".toByteArray())
        val bytes = foreign.readBytes()
        val inspector = SafReadOnlyIdentityInspector(context.contentResolver, tree, root)
        val before = inspector.snapshot()
        assertNotNull(before)
        val existing = DocumentsContract.buildDocumentUriUsingTree(tree, "root/foreign.bin")
        val result = inspector.inspectReturned(before, existing, "foreign.bin")
        assertEquals(SafCreationCandidate.Result.REJECT, result)
        assertArrayEquals(bytes, foreign.readBytes())
    }

    @Test fun newUriIsOnlyUnprovenEvenWhenBothInventoriesAgree() {
        File(base, "foreign.bin").writeText("preserve")
        val inspector = SafReadOnlyIdentityInspector(context.contentResolver, tree, root)
        val before = inspector.snapshot()
        assertNotNull(before)
        val candidate = DocumentsContract.createDocument(context.contentResolver, root,
            "application/octet-stream", "new.bin")
        assertNotNull(candidate)
        assertEquals(SafCreationCandidate.Result.UNPROVEN_CANDIDATE,
            inspector.inspectReturned(before, candidate, "new.bin"))
        assertEquals("preserve", File(base, "foreign.bin").readText())
        assertEquals(0L, File(base, "new.bin").length())
    }

    @Test fun crossScopeOrMalformedUriIsRejected() {
        val inspector = SafReadOnlyIdentityInspector(context.contentResolver, tree, root)
        val before = inspector.snapshot()
        val wrong = android.net.Uri.parse("content://unrelated.provider/tree/root/document/root%2Ff")
        assertEquals(SafCreationCandidate.Result.REJECT,
            inspector.inspectReturned(before, wrong, "f"))
        assertEquals(SafCreationCandidate.Result.REJECT,
            inspector.inspectReturned(before, null, "f"))
    }
}
