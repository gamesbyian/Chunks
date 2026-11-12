package io.github.gamesbyian.chunks

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Canonical Details files: an ordinary inventory refresh re-reads the exact job's files without
 * discarding the person's search/selection (TransferFilePager's own contract), keeps writes
 * disabled while stale, and still drops the list for another exact job. Loopback read-only
 * rTorrent fixture; JVM evidence only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class CanonicalRemoteFilesRefreshContinuityTest {
    @get:Rule val rule = createComposeRule()
    private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    private val methods = CopyOnWriteArrayList<String>()

    @After fun stop() = server.stop(0)

    private fun rows(count: Int) = (0 until count).joinToString("") {
        "<value><array><data><value><string>Season 1/Episode $it.mkv</string></value>" +
            "<value><i8>${1_000L + it}</i8></value><value><i8>1</i8></value></data></array></value>"
    }

    @Test fun refreshKeepsFileSearchAndSelectionButNotAnotherJob() {
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)!!.groupValues[1]
            methods += method
            val reply = if (method == "f.multicall")
                "<params><param><value><array><data>${rows(90)}</data></array></value></param></params>"
            else "<fault><value><struct><member><name>faultCode</name><value><int>1</int></value></member>" +
                "<member><name>faultString</name><value><string>no</string></value></member></struct></value></fault>"
            val bytes = "<methodResponse>$reply</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))
        val scope = remote.addAccountScope()
        val first = RemoteTransfer("a".repeat(40), "Season", 100, 30, false, "/shows", 0, 0)
        val transfer = mutableStateOf(first)
        val stale = mutableStateOf(false)
        val revision = mutableLongStateOf(1L)
        rule.setContent { ChunksTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            CanonicalRemoteFilesControls(remote, transfer.value, scope, snapshotStale = stale.value,
                mainView = true, snapshotRevision = revision.longValue,
                onMutationStarted = { error("Reading and refreshing files never writes") }, onSettled = {})
        } } } }

        rule.onNodeWithText("Files & priorities…").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("90 server-reported files").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Search this transfer's files"))
            .performTextInput("Episode 77")
        rule.onNodeWithContentDescription("Change priority for file 78: Season 1/Episode 77.mkv")
            .performScrollTo().performClick()
        rule.onNodeWithText("Edit one file priority").assertExists()

        // An ordinary refresh: inventory goes stale, then a new accepted revision arrives.
        rule.runOnIdle { stale.value = true }
        rule.waitForIdle()
        // The last verified list stays visible, but no change can be reviewed from it.
        rule.onNodeWithText("78. Season 1/Episode 77.mkv", substring = true).assertExists()
        assertTrue(rule.onAllNodesWithText("Review priority change").fetchSemanticsNodes().isEmpty())
        rule.runOnIdle { stale.value = false; revision.longValue = 2L }
        rule.waitUntil(15_000) { methods.count { it == "f.multicall" } == 2 && rule.onAllNodesWithText(
            "Reading this transfer's files", substring = true).fetchSemanticsNodes().isEmpty() }
        // Search and the selected file survived the refresh and its fresh exact-job re-read.
        rule.onNode(hasSetTextAction() and hasText("Episode 77")).assertExists()
        rule.onNodeWithText("Showing 1 of 90 file paths · filtered").assertExists()
        rule.onNodeWithText("Edit one file priority").assertExists()
        rule.onNodeWithText("Review priority change").performScrollTo().assertIsNotEnabled()

        // A different exact job never inherits the previous job's list or selection.
        rule.runOnIdle { transfer.value = first.copy(hash = "b".repeat(40), name = "Other") }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Season 1/Episode 77.mkv", substring = true)
            .fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("f.multicall", "f.multicall"), methods.toList())
        assertFalse(methods.any { it.startsWith("f.priority") || it.startsWith("d.") })
    }
}
