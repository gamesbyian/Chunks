package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose rendering of the experimental on-phone engine surfaces. */
@RunWith(AndroidJUnit4::class)
class LocalEngineScreenInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    @Test fun reviewNamesDestinationAndAddsStopped() {
        var confirmed = 0
        val destination = File("/storage/emulated/0/Android/data/io.github.gamesbyian.chunks/files/Downloads")
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalAddReview(TorrentMetainfoPreview("Internet Archive public sample", "a".repeat(40), 3,
                        734_003_200, 262_144), destination, busy = false, onConfirm = { confirmed++ }, onCancel = {})
                }
            }
        }
        rule.onNodeWithText("Saves into:", substring = true).assertExists()
        rule.onNodeWithText("Nothing downloads until you press Start.", substring = true).assertExists()
        capture("local-engine-add-review-real-compose")
        rule.onNodeWithText("Add stopped").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun pendingOrStaleAddReviewDisablesSubmissionButAllowsCancellation() {
        var confirmed = 0
        var cancelled = 0
        val destination = File("/storage/emulated/0/Android/data/io.github.gamesbyian.chunks/files/Downloads")
        rule.setContent {
            MaterialTheme {
                LocalAddReview(
                    TorrentMetainfoPreview("Retained after an uncertain Add", "c".repeat(40),
                        1, 1_024, 16_384),
                    destination, busy = true,
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ }
                )
            }
        }
        rule.onNodeWithText("Retained after an uncertain Add").assertExists()
        rule.onNodeWithText("Add stopped").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertEquals(0, confirmed)
        assertEquals(1, cancelled)
    }

    @Test fun transferCardRequiresConfirmationBeforeSessionOnlyRemoval() {
        var removed = 0
        val status = LocalTransferStatus("b".repeat(40), "Internet Archive public sample",
            LocalTransferState.DOWNLOADING, stopped = false, progressPpm = 420_000, wantedBytes = 1000,
            doneBytes = 420, payloadDownloadedBytes = 420, payloadUploadedBytes = 64, peers = 3,
            savePath = "/x", error = null)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalTransferCard(status, busy = false, onToggle = {}, onRemove = { removed++ })
                }
            }
        }
        rule.onNodeWithText("Downloading · 42% · 3 peers").assertExists()
        rule.onNodeWithText("Verified on disk 420 B of 1000 B", substring = true).assertExists()
        // Chunk boundaries are decorative; TalkBack receives continuous 42% progress.
        rule.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0.42f, 0f..1f)
        )).assertExists()
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("Remove…").performClick()
        assertEquals(0, removed)
        rule.onNodeWithText("Downloaded files stay in Chunks' folder", substring = true).assertExists()
        capture("local-engine-remove-confirm-real-compose")
        rule.onNodeWithText("Remove, keep files").performClick()
        assertEquals(1, removed)
    }

    @Test fun nativePerTransferPayloadRatesAreVisibleOnRunningCards() {
        val transfer = LocalTransferStatus("9".repeat(40), "Live peer fixture",
            LocalTransferState.DOWNLOADING, stopped = false, progressPpm = 25_000,
            wantedBytes = 1_000_000, doneBytes = 25_000,
            payloadDownloadedBytes = 25_000, payloadUploadedBytes = 2_000,
            peers = 1, savePath = "/x", error = null,
            downloadPayloadRateBytesPerSecond = 4096,
            uploadPayloadRateBytesPerSecond = 2048)
        rule.setContent {
            MaterialTheme {
                LocalTransferCard(transfer, busy = false, onToggle = {}, onRemove = {})
            }
        }
        rule.onNodeWithText("Live payload ↓", substring = true).assertExists()
        rule.onNodeWithText("↑", substring = true).assertExists()
        rule.onNodeWithText("Verified on disk", substring = true).assertExists()
    }

    @Test fun staleInventoryDisablesNativeTransferMutationsAndFileSelection() {
        var toggles = 0
        var removals = 0
        val status = LocalTransferStatus("f".repeat(40), "Previously observed transfer",
            LocalTransferState.SEEDING, stopped = false, progressPpm = 1_000_000,
            wantedBytes = 1000, doneBytes = 1000, payloadDownloadedBytes = 1000,
            payloadUploadedBytes = 2000, peers = 2, savePath = "/x", error = null)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalTransferCard(status, busy = true, onToggle = { toggles++ },
                        onRemove = { removals++ },
                        files = listOf(LocalTransferFile(0, "complete.bin", 1000, 1000, true)),
                        filesShown = true, onShowFiles = { toggles++ },
                        onFileWanted = { _, _ -> toggles++ })
                }
            }
        }
        rule.onNodeWithText("Previously observed transfer").assertExists()
        rule.onNodeWithText("Stop").assertIsNotEnabled()
        rule.onNodeWithText("Remove…").assertIsNotEnabled()
        rule.onNodeWithText("Hide files").assertIsNotEnabled()
        rule.onNodeWithText("complete.bin").assertExists().assertIsNotEnabled()
        assertEquals(0, toggles)
        assertEquals(0, removals)
    }

    @Test fun fileSelectionReportsEachToggleAndMarksSkippedFiles() {
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        val files = listOf(
            LocalTransferFile(0, "Season 1/Episode 01.mkv", 734_003_200, 367_001_600, wanted = true),
            LocalTransferFile(2, "Season 1/Sample.mkv", 10_485_760, 0, wanted = false))
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalFileSelection(files, busy = false) { index, wanted -> toggles += index to wanted }
                }
            }
        }
        rule.onNodeWithText("Episode 01.mkv").assertExists()
        rule.onNodeWithText("skipped", substring = true).assertExists()
        capture("local-engine-file-selection-real-compose")
        rule.onNodeWithText("Episode 01.mkv").performClick()
        rule.onNodeWithText("Sample.mkv").performClick()
        assertEquals(listOf(0 to false, 2 to true), toggles)
    }

    @Test fun selectionPagesReachEveryFileWithoutRenderingHugeLists() {
        val selected = mutableListOf<Pair<Int, Boolean>>()
        val files = (0 until 85).map { index ->
            LocalTransferFile(index, "Archive/File %03d.txt".format(index), 100L, 0L, true)
        }
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    LocalFileSelection(files, busy = false, onFileWanted = { index, wanted ->
                        selected += index to wanted
                    }, transferKey = "fixture")
                }
            }
        }
        rule.onNodeWithText("Files page 1 of 3 (85 files)").assertExists()
        rule.onNodeWithText("File 040.txt").assertDoesNotExist()
        rule.onNodeWithText("Previous page").assertIsNotEnabled()
        rule.onNodeWithText("Next page").performClick()
        rule.onNodeWithText("Files page 2 of 3 (85 files)").assertExists()
        rule.onNodeWithText("File 040.txt").performScrollTo().performClick()
        rule.onNodeWithText("File 080.txt").assertDoesNotExist()
        rule.onNodeWithText("Next page").performScrollTo().performClick()
        rule.onNodeWithText("Files page 3 of 3 (85 files)").assertExists()
        rule.onNodeWithText("File 084.txt").performScrollTo().performClick()
        rule.onNodeWithText("Next page").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(40 to false, 84 to false), selected)
    }

    @Test fun speedLimitsRefuseInvalidInputAndSaveKibibytes() {
        val prefs = context.getSharedPreferences("local-engine", android.content.Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        try {
            prefs.edit().remove("download-limit").remove("upload-limit").commit()
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().padding(16.dp)) { LocalSpeedLimits() }
                }
            }
            rule.onNodeWithText("Speed limits").assertExists()
            rule.onNodeWithText("Download").performTextReplacement("fast")
            rule.onNodeWithText("Save speed limits").assertIsNotEnabled()
            rule.onNodeWithText("Download").performTextReplacement("512")
            rule.onNodeWithText("Upload").performTextReplacement("64")
            rule.onNodeWithText("Save speed limits").assertIsEnabled()
            capture("local-engine-speed-limits-real-compose")
            rule.onNodeWithText("Save speed limits").performClick()
            // Native settings now require confirmed readback before durable save.
            // Never accept the older optimistic "Saved"/"Applied" strings.
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(
                    "Speed limits verified (if the engine is running) and saved on this phone."
                ).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(512 * 1024, prefs.getInt("download-limit", -1))
            assertEquals(64 * 1024, prefs.getInt("upload-limit", -1))
        } finally {
            val editor = prefs.edit().remove("download-limit").remove("upload-limit")
            (before["download-limit"] as? Int)?.let { editor.putInt("download-limit", it) }
            (before["upload-limit"] as? Int)?.let { editor.putInt("upload-limit", it) }
            editor.commit()
            LocalEngineHolder.current()?.setRateLimits(LocalEngineHolder.rateLimitsPreferred(context))
        }
    }

    @Test fun copyReviewRendersNatively() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LocalZipExtractionConfirm(
                        LocalZipExtractionPlan("Internet Archive public sample", "Movies", "Internet Archive public sample",
                            744_488_960, 3, 52_000_000_000, copy = true),
                        onConfirm = {}, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("Copy to new folder").assertIsEnabled()
        capture("local-engine-copy-review-real-compose")
    }
}
