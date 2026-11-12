package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Remote Save to precedence (owner report: a connected Seedhost showed "No usable default
 * Place was reported"; the compatibility matrix records `directory.default` empty there):
 * this Add's folder > the saved default for this exact account > rTorrent's absolute default.
 * Against a stateful loopback rTorrent double; JVM evidence only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteDestinationResolutionTest {
    @get:Rule val rule = createComposeRule()
    private val server = FakeRTorrent()
    @After fun stop() = server.close()

    private val magnet = "magnet:?xt=urn:btih:" + "c".repeat(40) +
        "&dn=Away&tr=https%3A%2F%2Ftracker.example%2Fannounce"

    @Test fun discoveryDistinguishesAbsoluteUnsetRelativeAndFailed() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials(server.endpoint, "", ""))
        server.directoryDefault = "/home/ian/downloads/"
        assertEquals(DefaultDirectoryReport.Absolute("/home/ian/downloads"), remote.defaultDirectoryReport())
        server.directoryDefault = ""
        assertEquals(DefaultDirectoryReport.Unset, remote.defaultDirectoryReport())
        server.directoryDefault = "~/torrents"
        assertEquals(DefaultDirectoryReport.NotAbsolute, remote.defaultDirectoryReport())
        server.directoryDefault = "./"
        assertEquals(DefaultDirectoryReport.NotAbsolute, remote.defaultDirectoryReport())
        server.directoryDefault = null // permission-style fault
        assertTrue(remote.defaultDirectoryReport() is DefaultDirectoryReport.Failed)
    }

    private fun show(saved: androidx.compose.runtime.MutableState<String?>,
                     destination: androidx.compose.runtime.MutableState<String>) {
        val remote = RTorrentRemote(RemoteCredentials(server.endpoint, "", ""))
        val account = remote.addAccountScope()
        val session = RemoteAddSession(account)
        rule.setContent { ChunksTheme { Surface {
            VerifiedAddScreen(remote, magnet, destination.value, onMagnet = {},
                onDestination = { destination.value = it }, onBack = {}, onComplete = {},
                onMutationStarted = { true }, onMutationSettled = {},
                finishInOrderScope = account, session = session,
                savedDefaultPlace = saved.value,
                onSaveDefaultPlace = { saved.value = it; true })
        } } }
    }

    private fun reveal(tag: String) = rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))

    @Test fun unsetServerDefaultNeedsOneChoiceThenTheSavedDefaultIsUsedWithoutTyping() {
        server.directoryDefault = ""
        val saved = mutableStateOf<String?>(null)
        val destination = mutableStateOf("")
        show(saved, destination)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("rTorrent has no default download folder configured", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Add is visible but blocked only because no folder is knowable yet.
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsNotEnabled()

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Custom server folder (optional)"))
        rule.onNodeWithText("Custom server folder (optional)").performTextReplacement("/home/ian/torrents")
        reveal(ChunksSemantics.ADD_REMOTE_SAVE_DEFAULT)
        rule.onNodeWithTag(ChunksSemantics.ADD_REMOTE_SAVE_DEFAULT).performClick()
        rule.waitForIdle()
        assertEquals("/home/ian/torrents", saved.value)
        assertEquals("The per-Add field is released to the saved default", "", destination.value)
        reveal(ChunksSemantics.ADD_REMOTE_SAVE_TO)
        rule.onNodeWithText("Using this server's saved default folder: /home/ian/torrents.").assertExists()
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(15_000) { server.jobs.containsKey("c".repeat(40)) }
        assertEquals("The saved folder is sent explicitly and verified",
            "/home/ian/torrents", server.jobs.getValue("c".repeat(40)).directory)
        assertTrue(server.calls.single { it.first.startsWith("load.") }.second
            .contains("d.directory.set=/home/ian/torrents"))
    }

    @Test fun anAbsoluteServerDefaultIsUsedAndAnExplicitFolderIsNeverOverwrittenByLateDiscovery() {
        server.directoryDefault = "/srv/default"
        val saved = mutableStateOf<String?>(null)
        val destination = mutableStateOf("/srv/chosen")
        show(saved, destination)
        rule.waitUntil(15_000) { server.methods.contains("directory.default") }
        rule.waitForIdle()
        assertEquals("/srv/chosen", destination.value)
        reveal(ChunksSemantics.ADD_REMOTE_SAVE_TO)
        rule.onNodeWithText("Using a custom folder for this Add only", substring = true).assertExists()
        // Clearing the field falls back to rTorrent's own absolute default.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Reset to rTorrent default folder"))
        rule.onNodeWithText("Reset to rTorrent default folder").performClick()
        reveal(ChunksSemantics.ADD_REMOTE_SAVE_TO)
        rule.onNodeWithText("Using rTorrent default Place: /srv/default", substring = true).assertExists()
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()
    }

    @Test fun aFailedDiscoveryIsRetryableAndNotMistakenForAnUnsetDefault() {
        server.directoryDefault = null
        val saved = mutableStateOf<String?>(null)
        val destination = mutableStateOf("")
        show(saved, destination)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("could not be read (connection or permission)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        server.directoryDefault = "/srv/recovered"
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Check the server's default folder again"))
        rule.onNodeWithText("Check the server's default folder again").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Using rTorrent default Place: /srv/recovered", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
