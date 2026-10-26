package io.github.gamesbyian.chunks

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.model.DeviceSpaceSnapshot
import io.github.gamesbyian.chunks.model.LocalFolderInventory
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneStorageCapInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeCapSaveAndClearNeverPretendToEnforceQuota() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(LocalEngineHolder.appDownloads(context))
        val store = LocalCapPreferenceStore(context)
        val gib = 1024L * 1024 * 1024
        store.set(root, null)
        try {
            val used: (Context) -> LocalFolderInventory = {
                LocalFolderInventory(90L * gib / 100L, 2, true)
            }
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PhoneStoragePanel(
                            readSpace = { DeviceSpaceSnapshot(2 * gib, gib, false) },
                            readFolder = used
                        )
                    }
                }
            }
            rule.onNodeWithTag("phone-advisory-cap-input").performScrollTo().performTextInput("1")
            rule.onNodeWithText("Save advisory limit").performScrollTo().performClick()
            rule.onNodeWithText("Current advisory limit: 1.0 GiB").assertExists()
            // The bounded folder survey is asynchronous. Test the 85%-tier
            // at 90% use to avoid a one-byte ceil/floor threshold ambiguity.
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Warning: download-folder logical sizes reached 85%",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Chunks does not yet enforce a local torrent storage cap.", substring = true)
                .assertExists()
            assertEquals(gib, LocalCapPreferenceStore(context).get(root))
            rule.onNodeWithText("Clear advisory limit").performScrollTo().performClick()
            assertNull(LocalCapPreferenceStore(context).get(root))
            rule.onNodeWithText("Advisory limit cleared.", substring = true).assertExists()
        } finally {
            store.set(root, null)
        }
    }
}
