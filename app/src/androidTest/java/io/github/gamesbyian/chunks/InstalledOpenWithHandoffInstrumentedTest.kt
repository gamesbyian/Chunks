package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android task + real MainActivity tier for the owner's Open With regression.
 * Unlike Robolectric, this uses Android's ActivityTaskManager and a real document
 * provider, but the provider lives in this app. It does not establish grants
 * from external apps or physical Pixel/Files/Drive acceptance.
 *
 * Merely reaching review must never authorize Add, Start or network work.
 */
@RunWith(AndroidJUnit4::class)
class InstalledOpenWithHandoffInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)

    @Before fun prepare() { base.deleteRecursively(); base.mkdirs() }
    @After fun clean() { base.deleteRecursively() }

    private fun b(value: String) = "${value.toByteArray(Charsets.UTF_8).size}:$value"

    private fun metainfo(name: String): ByteArray = (
        "d8:announce" + b("https://tracker.example/announce") +
            "4:infod6:lengthi5e4:name" + b(name) +
            "12:piece lengthi16384e6:pieces20:01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)

    private fun document(name: String): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        // Android chooser only sees generic binary, not the torrent's bencoded
        // content. The Add review must inspect those bytes independently.
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "application/octet-stream", "incoming.torrent"
        ))
        context.contentResolver.openOutputStream(uri, "wt").use { stream ->
            requireNotNull(stream).write(metainfo(name))
        }
        return uri
    }

    private fun openWith(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setClass(context, MainActivity::class.java)
        setDataAndType(uri, "application/octet-stream")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun waitForReview(name: String) {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(name, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun installedGenericBinaryOpenWithKeepsUnsubmittedReviewAcrossBackgroundAndRecreation() {
        val name = "installed-open-with-fixture.mkv"
        val uri = document(name)
        assertTrue(uri.scheme == "content")
        ActivityScenario.launch<MainActivity>(openWith(uri)).use { scenario ->
            waitForReview(name)
            scenario.onActivity {
                assertFalse("Open With must not close Chunks after accepting the document", it.isFinishing)
            }
            // The review may be temporarily backgrounded while the chooser or
            // source app regains focus. Its unsubmitted document must survive.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForReview(name)
            // An actual Android Activity recreation is distinct from a
            // disposable Compose route and must keep the Add draft in memory.
            scenario.recreate()
            waitForReview(name)
            scenario.onActivity {
                assertFalse("Recreated Chunks must still own its Add review", it.isFinishing)
            }
        }
    }
}
