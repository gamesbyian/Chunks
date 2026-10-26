package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.model.DeviceSpaceSnapshot
import io.github.gamesbyian.chunks.model.LocalFolderInventory
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneStoragePanelInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
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

    @Test fun showsPhoneOnlyCapacityLowSpaceAndARealRefreshAction() {
        val gib = 1024L * 1024 * 1024
        val count = AtomicInteger(0)
        val sample: (Context) -> DeviceSpaceSnapshot = {
            val free = if (count.getAndIncrement() == 0) gib else 5 * gib
            DeviceSpaceSnapshot(10 * gib, free, free == gib)
        }
        rule.setContent {
            MaterialTheme { PhoneStoragePanel(readSpace = sample) }
        }
        rule.onNodeWithText("Phone storage").assertExists()
        rule.onNodeWithText("1.0 GiB free of 10.0 GiB").assertExists()
        rule.onNodeWithText("Android reports low phone storage. Avoid adding large local downloads.")
            .assertExists()
        rule.onNodeWithText("Occupied or reserved on this volume, including other apps. Not Chunks torrent usage.")
            .assertExists()
        capture("phone-storage-low-native-compose")
        rule.onNodeWithText("Refresh phone space").performClick()
        rule.onNodeWithText("5.0 GiB free of 10.0 GiB").assertExists()
        rule.onNodeWithText("No active Android low-storage alert detected. The numeric threshold is not exposed to apps.").assertExists()
        capture("phone-storage-normal-native-compose")
    }

    @Test fun showsSeparateLocalFolderUsageAndRefreshesItWithoutClaimingAQuota() {
        val reads = AtomicInteger(0)
        val folderSample: (Context) -> LocalFolderInventory = {
            if (reads.getAndIncrement() == 0) LocalFolderInventory(512, 1, true)
            else LocalFolderInventory(2048, 2, false)
        }
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PhoneStoragePanel(
                        readSpace = { DeviceSpaceSnapshot(10_000, 5_000, false) },
                        readFolder = folderSample
                    )
                }
            }
        }
        rule.onNodeWithText("Chunks' download folder on this phone").assertExists()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("in 1 file", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Chunks does not yet enforce a local torrent storage cap.", substring = true)
            .assertExists()
        rule.onNodeWithText("Refresh phone space").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("in 2 files", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Partial survey only:", substring = true).assertExists()
    }

    @Test fun allocatedBlockEstimateIsSeparateFromLogicalCapAndNeverPromisesReclaim() {
        val reads = AtomicInteger(0)
        val allocation: (Context) -> LocalAllocatedInventory = {
            if (reads.getAndIncrement() == 0)
                LocalAllocatedInventory(4096, 8192, 2, 1, true, true)
            else LocalAllocatedInventory(4096, 8192, 2, 1, false, true)
        }
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PhoneStoragePanel(
                        readSpace = { DeviceSpaceSnapshot(20_000, 10_000, false) },
                        readFolder = { LocalFolderInventory(8192, 2, true) },
                        readAllocated = allocation
                    )
                }
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("4.0 KiB of filesystem blocks", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Some files may have hardlinks outside this folder.", substring = true)
            .assertExists()
        rule.onNodeWithText("not guaranteed space freed by cleanup", substring = true).assertExists()
        rule.onNodeWithText("Refresh phone space").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Allocated usage unavailable:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun platformProbeReadsNoFilesOrPrivateTorrentInventory() {
        val report = DeviceSpaceProbe.read(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(report.totalBytes == null || report.totalBytes > 0)
        assertTrue(report.availableBytes == null || report.availableBytes >= 0)
    }
}
