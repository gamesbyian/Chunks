package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFilesOnDiskCheckTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun checkRunsOnlyWhenAskedAndListsProblemFiles() {
        val root = temp.newFolder("save")
        File(root, "Show").mkdirs()
        File(root, "Show/ep1.mkv").writeBytes(ByteArray(10))
        val files = listOf(
            LocalTransferFile(0, "Show/ep1.mkv", 10, 10, true),
            LocalTransferFile(1, "Show/ep2.mkv", 10, 10, true)
        )
        var calls = 0
        rule.setContent {
            ChunksTheme {
                LocalFilesOnDiskCheck("a".repeat(40), root.path, files,
                    check = { dir, list -> calls++; LocalFileDiskCheck.check(dir, list) })
            }
        }
        assertEquals(0, calls)
        rule.onNodeWithText("Check files on disk (read-only)").performClick()
        rule.waitUntil(5_000) { calls == 1 && rule.onAllNodes(
            androidx.compose.ui.test.hasText("1 of 2 files need attention", substring = true))
            .fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Show/ep2.mkv", substring = true).assertIsDisplayed()
        // Read-only: nothing was created for the missing file.
        assertTrue(!File(root, "Show/ep2.mkv").exists())
    }

    @Test fun nonFilePathFolderExplainsInsteadOfChecking() {
        rule.setContent {
            ChunksTheme {
                LocalFilesOnDiskCheck("b".repeat(40), "content://downloads/tree/1",
                    listOf(LocalTransferFile(0, "x", 1, 0, true)))
            }
        }
        rule.onNodeWithText("can't be checked here", substring = true).assertIsDisplayed()
    }
}
