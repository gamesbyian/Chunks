package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AliasBatchReviewInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun saveImage(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(out)))
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
    }

    @Test fun previewCommitAndUndoHaveNoAutomaticWrites() {
        val scope = "batch-fixture-" + System.nanoTime()
        val store = TransferAliasStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val hash1 = "A".repeat(40)
        val hash2 = "B".repeat(40)
        val transfers = listOf(
            RemoteTransfer(hash1, "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv",
                100, 80, true, "/remote"),
            RemoteTransfer(hash2, "A.Movie.2024.1080p.mkv",
                100, 30, false, "/remote")
        )
        var revision by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                AliasBatchReviewScreen(
                    transfers, store, scope, revision,
                    onAliasesChanged = { revision++ },
                    onBack = {}
                )
            }
        }
        rule.onNodeWithText("2 suggestions ready for review").assertExists()
        rule.onNodeWithContentDescription(
            "Select proposed display name for Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv"
        ).assertExists()
        rule.runOnIdle {
            assertNull(store.get(scope, hash1))
            assertNull(store.get(scope, hash2))
        }
        saveImage("batch-alias-proposals-native-compose")
        rule.onNodeWithText("Select shown").performClick()
        rule.onNodeWithText("Review 2 selected names").performClick()
        rule.runOnIdle {
            assertNull(store.get(scope, hash1))
            assertNull(store.get(scope, hash2))
        }
        rule.onNodeWithText("Confirm 2 display names").assertExists()
        rule.onNodeWithText("Apply these local display names").performClick()
        rule.runOnIdle {
            assertEquals("Celebrity Jeopardy S04E02", store.get(scope, hash1))
            assertEquals("A Movie (2024)", store.get(scope, hash2))
        }
        saveImage("batch-alias-saved-native-compose")
        rule.onNodeWithText("Undo last batch").performClick()
        rule.runOnIdle {
            assertNull(store.get(scope, hash1))
            assertNull(store.get(scope, hash2))
        }
    }

    @Test fun userEditedBatchNameWinsAndUndoCannotEraseLaterManualEdit() {
        val scope = "batch-edit-fixture-" + System.nanoTime()
        val store = TransferAliasStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val hash = "C".repeat(40)
        var revision by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                AliasBatchReviewScreen(
                    listOf(RemoteTransfer(hash, "Another.Show.S01E03.1080p.mkv",
                        100, 50, false, "/remote")),
                    store, scope, revision,
                    onAliasesChanged = { revision++ },
                    onBack = {}
                )
            }
        }
        rule.onNodeWithText("Select shown").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("My chosen title")
        rule.onNodeWithText("Review 1 selected names").performClick()
        rule.onNodeWithText("Display as: My chosen title").assertExists()
        rule.onNodeWithText("Apply these local display names").performClick()
        rule.runOnIdle {
            assertEquals("My chosen title", store.get(scope, hash))
            assertEquals(true, store.save(scope, hash, "Changed later"))
        }
        rule.onNodeWithText("Undo last batch").performClick()
        rule.runOnIdle {
            assertEquals("Changed later", store.get(scope, hash))
        }
        rule.onNodeWithText("Some names have changed; nothing was undone. Review them individually.")
            .assertExists()
    }
    @Test fun staleBatchFailsAtomicallyRatherThanOverwriteAManualAlias() {
        val store = TransferAliasStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val scope = "batch-conflict-" + System.nanoTime()
        val a = "D".repeat(40)
        val b = "E".repeat(40)
        val proposed = listOf(
            io.github.gamesbyian.chunks.model.AliasReviewWrite(a, "A.Show.S01E01.mkv", "A Show S01E01"),
            io.github.gamesbyian.chunks.model.AliasReviewWrite(b, "B.Movie.2024.mkv", "B Movie (2024)")
        )
        assertEquals(true, store.save(scope, b, "My existing custom title"))
        assertEquals(false, store.saveReviewedBatch(scope, proposed))
        assertNull(store.get(scope, a))
        assertEquals("My existing custom title", store.get(scope, b))
    }

}
