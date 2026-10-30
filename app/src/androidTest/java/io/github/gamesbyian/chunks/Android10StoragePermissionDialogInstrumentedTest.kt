package io.github.gamesbyian.chunks

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 non-clobber containment regression on Android 10. No user permission
 * should grant the current native libtorrent backend authority to write
 * arbitrary shared Downloads paths. This replaces the former permission-
 * dialog test while direct public backing is quarantined.
 */
@RunWith(AndroidJUnit4::class)
class Android10StoragePermissionDialogInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun granted() = LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @Test fun publicNativeBackingDoesNotRequestStoragePermissionOrBecomeDefault() {
        assumeTrue("Android 10 only", Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
        assumeTrue("Permission-negative control requires a fresh ungranted install", !granted())
        rule.setContent { MaterialTheme { LocalEngineScreen() } }
        val button = "Allow storage access for Downloads/" + LocalDownloadPlaces.PUBLIC_FOLDER_NAME

        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Saves to: Chunks app folder")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(button).assertDoesNotExist()
        val choices = LocalDownloadPlaces.choices(context)
        val shared = choices.single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        assertFalse("Shared writer must be disabled even when permissions could be granted", shared.verified)
        assertNull(LocalDownloadPlaces.materialize(shared.place))
        assertTrue("Viewing the screen must not request storage permissions", !granted())
    }
}
