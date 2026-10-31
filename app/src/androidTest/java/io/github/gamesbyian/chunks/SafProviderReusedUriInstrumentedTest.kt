package io.github.gamesbyian.chunks

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.ReusedUriDocumentsProvider
import io.github.gamesbyian.chunks.storage.SafCreationCandidate
import io.github.gamesbyian.chunks.storage.SafReadOnlyIdentityInspector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real ContentResolver call whose malicious create returns an existing URI. */
@RunWith(AndroidJUnit4::class)
class SafProviderReusedUriInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val foreign get() = ReusedUriDocumentsProvider.foreign(context)
    private val tree get() = ReusedUriDocumentsProvider.treeUri(context)
    private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))

    @Before fun setup() {
        val dir = ReusedUriDocumentsProvider.baseDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        foreign.writeText("unrelated data must survive")
    }
    @After fun cleanup() { ReusedUriDocumentsProvider.baseDir(context).deleteRecursively() }

    @Test fun actualProviderReturningExistingUriIsRejectedAndLeavesForeignBytesUnchanged() {
        val beforeBytes = foreign.readBytes()
        val inspector = SafReadOnlyIdentityInspector(context.contentResolver, tree, root)
        val before = inspector.snapshot()
        assertNotNull(before)
        val suspicious = DocumentsContract.createDocument(context.contentResolver, root,
            "text/plain", "innocent-new-file.txt")
        assertNotNull(suspicious)
        assertEquals(SafCreationCandidate.Result.REJECT,
            inspector.inspectReturned(before, suspicious, "innocent-new-file.txt"))
        assertArrayEquals(beforeBytes, foreign.readBytes())
    }

    @Test fun nameMatchingExistingUriAlsoRefuses() {
        val before = foreign.readBytes()
        val inspector = SafReadOnlyIdentityInspector(context.contentResolver, tree, root)
        val start = inspector.snapshot()
        val suspicious = DocumentsContract.createDocument(context.contentResolver, root,
            "text/plain", "foreign.txt")
        assertEquals(SafCreationCandidate.Result.REJECT,
            inspector.inspectReturned(start, suspicious, "foreign.txt"))
        assertArrayEquals(before, foreign.readBytes())
    }
}
