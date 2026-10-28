package io.github.gamesbyian.chunks

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 10's REAL system permission dialog, reached from the phone screen's
 * own "Allow storage access" button: the user taps the button, then Allow, and
 * the picker re-probes and switches to Downloads/Chunks Downloads. Must run on
 * a fresh install (no prior grant); the API 29 workflow runs it first, alone.
 */
@RunWith(AndroidJUnit4::class)
class Android10StoragePermissionDialogInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun granted() = LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @Test fun allowingTheSystemDialogSwitchesNewAddsToSharedDownloads() {
        assumeTrue("Android 10 only", Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
        assumeTrue("Needs a fresh install without the storage grant", !granted())
        rule.setContent { MaterialTheme { LocalEngineScreen() } }

        val button = "Allow storage access for Downloads/" + LocalDownloadPlaces.PUBLIC_FOLDER_NAME
        rule.waitUntil(30_000) { rule.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
        rule.onNodeWithText(button).performScrollTo().performClick()

        // The platform permission controller, not Chunks, draws this dialog.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val allow = device.wait(Until.findObject(
            By.res("com.android.permissioncontroller", "permission_allow_button")), 20_000)
        assertNotNull("Android's storage permission dialog did not appear", allow)
        allow.click()

        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Saves to: Downloads/" + LocalDownloadPlaces.PUBLIC_FOLDER_NAME)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Both storage permissions are granted after Allow", granted())
        rule.onNodeWithText(button).assertDoesNotExist()
    }
}
