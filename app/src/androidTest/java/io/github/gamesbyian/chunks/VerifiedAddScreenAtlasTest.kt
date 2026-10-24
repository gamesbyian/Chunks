package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose screenshot + semantic existence checks; no external server. */
@RunWith(AndroidJUnit4::class)
class VerifiedAddScreenAtlasTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote by lazy {
        RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    }
    private val validMagnet = "magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=Public%20test"

    private fun save(name: String) {
        val image = rule.onNode(isRoot()).captureToImage().asAndroidBitmap()
        // Gradle instrumentation cleanup can remove app-specific external files before adb pull.
        // Use Android's public MediaStore collection so native captures survive test teardown.
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore must accept native screenshot", uri)
        resolver.openOutputStream(uri!!).use { stream ->
            val output = requireNotNull(stream) { "MediaStore output must open" }
            assertTrue("Native PNG screenshot must be written",
                image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val publish = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertTrue("Screenshot must be made readable by adb", resolver.update(uri, publish, null, null) == 1)
    }

    @Test fun reviewedStoppedAddShowsExactHashAndDestination() {
        var magnet by mutableStateOf(validMagnet)
        var destination by mutableStateOf("/home/dev/chunks-public-test")
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(remote, magnet, destination,
                    onMagnet = { magnet = it },
                    onDestination = { destination = it },
                    onBack = {}, onComplete = {})
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Review stopped Add"))
        rule.onNodeWithText("Review stopped Add").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm: add stopped"))
        rule.onNodeWithText("Confirm: add stopped").assertExists()
        rule.onNodeWithText("Infohash: " + "ab".repeat(20)).assertExists()
        save("add-confirmation-real-compose")
    }

    @Test fun uncertainAddOffersReadOnlyReconciliationInsteadOfRepeatSubmit() {
        var magnet by mutableStateOf(validMagnet)
        var destination by mutableStateOf("/home/dev/chunks-public-test")
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(remote, magnet, destination,
                    onMagnet = { magnet = it },
                    onDestination = { destination = it },
                    onBack = {}, onComplete = {})
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Review stopped Add"))
        rule.onNodeWithText("Review stopped Add").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm: add stopped"))
        rule.onNodeWithText("Confirm: add stopped").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Check server state (read only)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(
            hasText("Check server state (read only)"))
        rule.onNodeWithText("Confirm: add stopped").assertDoesNotExist()
        rule.onNodeWithText("Check server state (read only)").assertExists()
        save("add-uncertain-recheck-real-compose")
    }

    @Test fun confirmedSessionRemovalShowsExactTargetWithoutSendingRpc() {
        val transfer = RemoteTransfer(
            hash = "ab".repeat(20), name = "A public example with a particularly long torrent title",
            sizeBytes = 128L, completedBytes = 128L, started = false,
            directory = "/home/dev/chunks-public-test"
        )
        rule.setContent {
            MaterialTheme {
                TransferSessionRemoveControl(remote, transfer,
                    snapshotStale = false, mainView = true, onSettled = {})
            }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Confirm removal from rTorrent").assertExists()
        rule.onNodeWithText("Infohash: " + "ab".repeat(20)).assertExists()
        rule.onNodeWithText("Current directory: /home/dev/chunks-public-test").assertExists()
        rule.onNodeWithText("Confirm remove").assertExists()
        save("remove-confirmation-real-compose")
    }

    @Test fun nativeFileInventoryCanPagePastFortyWithoutLosingIndices() {
        val files = (0 until 105).map { index ->
            RemoteFile(
                "Season-" + (index / 10) + "/A-very-long-descriptive-episode-name-" +
                    index.toString().padStart(3, '0') + "-public-example.mkv",
                10_000L + index, if (index % 3 == 0) 0 else 1
            )
        }
        rule.setContent {
            MaterialTheme {
                TransferFilePager(remote, "ab".repeat(20), files,
                    snapshotStale = false, mainView = true, onSettled = {})
            }
        }
        rule.onNodeWithText("Showing 1–40 of 105 files").assertExists()
        rule.onNodeWithText("Next 40").performClick()
        rule.onNodeWithText("Showing 41–80 of 105 files").assertExists()
        rule.onNodeWithText("Page 2 of 3").assertExists()
        rule.onAllNodesWithText("Priority").onFirst().performClick()
        rule.onNodeWithText("Edit one file priority").assertExists()
        rule.onNodeWithText("Close").performClick()
        save("files-dense-second-page-real-compose")
        rule.onNodeWithText("Next 40").performClick()
        rule.onNodeWithText("Showing 81–105 of 105 files").assertExists()
        rule.onNodeWithText("Previous 40").performClick()
        rule.onNodeWithText("Showing 41–80 of 105 files").assertExists()
    }

    @Test fun staleOrDifferentViewCannotOfferSessionRemoval() {
        val transfer = RemoteTransfer(
            hash = "ab".repeat(20), name = "Public example", sizeBytes = 8L,
            completedBytes = 8L, started = false, directory = "/home/dev/test"
        )
        rule.setContent {
            MaterialTheme {
                TransferSessionRemoveControl(remote, transfer,
                    snapshotStale = true, mainView = true, onSettled = {})
            }
        }
        rule.onNodeWithText("Refresh this stale server snapshot before removing a transfer.").assertExists()
        rule.onNodeWithText("Confirm remove").assertDoesNotExist()
    }

    @Test fun nativeFilePriorityConfirmationShowsExactTargetAndDoesNotSendRpc() {
        val file = RemoteFile("Public Bundle/An unusually long archival audio example.ogg", 1024, 1)
        rule.setContent {
            MaterialTheme {
                TransferFilePriorityControl(
                    remote, "ab".repeat(20), 7, file,
                    snapshotStale = false, mainView = true, onSettled = {})
            }
        }
        rule.onNodeWithText("High").performClick()
        rule.onNodeWithText("Review priority change").performClick()
        rule.onNodeWithText("Confirm file priority change").assertExists()
        rule.onNodeWithText("Exact infohash: " + "ab".repeat(20)).assertExists()
        rule.onNodeWithText("Confirm priority").assertExists()
        save("file-priority-confirmation-real-compose")
    }

    @Test fun staleFilePriorityControlCannotOfferMutation() {
        val file = RemoteFile("Public Bundle/Example.ogg", 10L, 1)
        rule.setContent {
            MaterialTheme {
                TransferFilePriorityControl(
                    remote, "ab".repeat(20), 0, file,
                    snapshotStale = true, mainView = true, onSettled = {})
            }
        }
        rule.onNodeWithText("Review priority change").assertDoesNotExist()
        rule.onNodeWithText("Confirm priority").assertDoesNotExist()
    }

    @Test fun traversalDestinationCannotReachConfirmation() {
        var magnet by mutableStateOf(validMagnet)
        var destination by mutableStateOf("/home/dev/../outside")
        rule.setContent {
            MaterialTheme {
                VerifiedAddScreen(remote, magnet, destination,
                    onMagnet = { magnet = it },
                    onDestination = { destination = it },
                    onBack = {}, onComplete = {})
            }
        }
        rule.onNodeWithText("Review stopped Add").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(
            "Invalid destination: remove repeated slashes, '.' and '..' path segments."
        ))
        rule.onNodeWithText("Invalid destination: remove repeated slashes, '.' and '..' path segments.").assertExists()
        save("add-invalid-path-real-compose")
    }
}
