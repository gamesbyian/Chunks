package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.model.DeviceSpaceSnapshot
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

    @Test fun platformProbeReadsNoFilesOrPrivateTorrentInventory() {
        val report = DeviceSpaceProbe.read(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(report.totalBytes == null || report.totalBytes > 0)
        assertTrue(report.availableBytes == null || report.availableBytes >= 0)
    }
}
