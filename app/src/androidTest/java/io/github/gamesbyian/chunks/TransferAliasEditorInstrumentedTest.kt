package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferAliasEditorInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
    }

    @Test fun selectedSuggestionSurvivesLazyListDisposalBeforeExplicitSave() {
        var alias by mutableStateOf<String?>(null)
        rule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "editor") {
                        TransferAliasEditor(
                            originalName = "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv",
                            hash = "C".repeat(40),
                            alias = alias,
                            onSave = { alias = it; true }
                        )
                    }
                    items(40) { index ->
                        Text("Other row $index", modifier = Modifier.height(120.dp))
                    }
                }
            }
        }
        rule.onNodeWithText("Change list name").performClick()
        rule.onNodeWithText("Celebrity Jeopardy S04E02").performClick()
        // Detach the entire editor item, not just scroll within it.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save name"))
        rule.onNodeWithText("Save name").performClick()
        rule.runOnIdle { assertEquals("Celebrity Jeopardy S04E02", alias) }
    }

    @Test fun suggestionsAreOnlyDraftsAndResetRestoresOriginalQueueName() {
        val torrentName = "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv"
        var alias by mutableStateOf<String?>(null)
        val committed = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    TransferAliasEditor(
                        originalName = torrentName,
                        hash = "A".repeat(40),
                        alias = alias,
                        onSave = {
                            committed.add(it)
                            alias = it
                            true
                        }
                    )
                }
            }
        }

        rule.onNodeWithText("Change list name").performClick()
        rule.onNodeWithText("Extracted show title, season and episode from filename").assertExists()
        capture("alias-suggestions-real-compose")
        rule.onNodeWithText("Celebrity Jeopardy S04E02").performClick()
        rule.runOnIdle { assertEquals(0, committed.size) } // recommendation never auto-applies
        rule.onNodeWithText("Save name").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Celebrity Jeopardy S04E02"), committed)
            assertEquals("Celebrity Jeopardy S04E02", alias)
        }
        capture("alias-saved-real-compose")
        rule.onNodeWithText("Edit list name").performClick()
        rule.onNodeWithText("Use original torrent name").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Celebrity Jeopardy S04E02", null), committed)
            assertEquals(null, alias)
        }
        rule.onNodeWithText("Change list name").assertExists()
    }
}
