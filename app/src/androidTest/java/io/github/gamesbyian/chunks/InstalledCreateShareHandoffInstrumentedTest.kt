package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.create.SharedCreateDocument
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android task and actual ACTION_SEND → Create torrent alias.
 * Uses a disposable debug DocumentsProvider, so this does not claim that a
 * physical Pixel's Files/Drive share sheet or third-party grants have passed.
 * No torrent is built, added, seeded or shared by this test.
 */
@RunWith(AndroidJUnit4::class)
class InstalledCreateShareHandoffInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)

    @Before fun prepare() { base.deleteRecursively(); base.mkdirs() }
    @After fun clean() { base.deleteRecursively() }

    private fun document(): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "text/plain", "create-share-fixture.txt"))
        context.contentResolver.openOutputStream(uri, "wt").use {
            requireNotNull(it).write("A local file; no torrent should exist yet.".toByteArray())
        }
        return uri
    }

    private fun createShare(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        component = ComponentName(context.packageName, SharedCreateDocument.ALIAS)
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "reviewed file", uri)
        // A cold external share must enter a clean Chunks task. Recreation must
        // not replay this one-shot Intent or silently perform Create/Seed.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun ready() {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("create-share-fixture.txt", substring = true))
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Make it shareable.", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Torrent created").assertDoesNotExist()
        compose.onNodeWithText("Not seeding yet").assertDoesNotExist()
    }

    @Test fun externalCreateShareStaysInReadOnlyReviewAfterActivityRecreation() {
        val uri = document()
        ActivityScenario.launch<MainActivity>(createShare(uri)).use { scenario ->
            ready()
            scenario.onActivity {
                assertFalse("Create review may not finish the Chunks task", it.isFinishing)
            }
            scenario.recreate()
            ready()
            scenario.onActivity {
                assertFalse("Recreated Create review may not finish Chunks", it.isFinishing)
            }
        }
    }
}
