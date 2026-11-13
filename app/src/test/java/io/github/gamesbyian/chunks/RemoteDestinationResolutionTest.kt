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
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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
                     destination: androidx.compose.runtime.MutableState<String>, start: Boolean = true) {
        val remote = RTorrentRemote(RemoteCredentials(server.endpoint, "", ""))
        val account = remote.addAccountScope()
        val session = RemoteAddSession(account)
        rule.setContent { ChunksTheme { Surface {
            VerifiedAddScreen(remote, magnet, destination.value, onMagnet = {},
                onDestination = { destination.value = it }, onBack = {}, onComplete = {},
                onMutationStarted = { true }, onMutationSettled = {},
                finishInOrderScope = account, session = session,
                addStartState = mutableStateOf(start),
                savedDefaultPlace = saved.value,
                onSaveDefaultPlace = { saved.value = it; true })
        } } }
    }

    private fun reveal(tag: String) = rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))

    @Test fun anExplicitSavedDefaultStillTakesPrecedenceOverAnUnknownNativeDefault() {
        server.directoryDefault = ""
        val saved = mutableStateOf<String?>(null)
        val destination = mutableStateOf("")
        show(saved, destination)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("rTorrent has no default download folder configured", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Unknown default is an explicit native selection; optional means optional.
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled()

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

    /** Behavioral + data-integrity: blank optional folder sends one native Add and verifies actual Place. */
    @Test fun blankOptionalFolderSubmitsAtNativeDefaultWithoutInventingAnOverride() {
        server.directoryDefault = ""
        show(mutableStateOf(null), mutableStateOf(""))
        reveal(ChunksSemantics.ADD_REMOTE_SAVE_TO)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("actual folder must be confirmed after Add", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-native-default-unknown.png")
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Verified: added and started", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val load = server.calls.single { it.first.startsWith("load.") }
        assertEquals(2, load.second.size)
        assertEquals("/home/user", server.jobs.getValue("c".repeat(40)).directory)
        assertTrue(server.methods.contains("d.directory"))
    }

    /** Ordinary native magnet journey: the unknown payload Place cannot be certified until BEP9. */
    @Test fun nativeMetadataWaitsForPayloadPlaceThenReadOnlyRecoveryConfirmsWithoutAnotherWrite() {
        server.directoryDefault = ""
        server.metadataPending = true
        show(mutableStateOf(null), mutableStateOf(""))
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled().performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Waiting for magnet metadata. rTorrent reports this exact transfer active", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Verified: added and started", substring = true).assertDoesNotExist()
        rule.onNodeWithText("View verified transfer in rTorrent").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            hasText("Waiting for magnet metadata.", substring = true))
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-native-metadata-pending.png")
        val hash = "c".repeat(40)
        assertTrue(server.jobs.getValue(hash).started)
        server.metadataPending = false // independently supplied metadata, not another Add
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Check server state (read only)"))
        rule.onNodeWithText("Check server state (read only)").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Server now confirms this exact hash, destination and active engine.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, server.loads().size)
        assertTrue(server.methods.none { it in setOf("d.open", "d.start", "d.resume", "d.erase") })
        assertEquals("/home/user", server.jobs.getValue(hash).directory)
    }

    @Test fun anExternallyStartedStoppedIntentKeepsItsMismatchInsteadOfHealthyStartGuidance() {
        server.directoryDefault = ""
        server.metadataPending = true
        server.startStoppedLoads = true
        show(mutableStateOf(null), mutableStateOf(""), start = false)
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Fetching magnet metadata.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("The Add was not fully verified; no Start was sent.", substring = true).assertExists()
        rule.onNodeWithText("Waiting for magnet metadata. rTorrent reports this exact transfer active", substring = true)
            .assertDoesNotExist()
        rule.onNodeWithText("View verified transfer in rTorrent").assertDoesNotExist()
        assertEquals("load.verbose", server.loads().single())
        assertTrue(server.methods.none { it in setOf("d.open", "d.start", "d.resume") })
    }

    /** Behavioral/presentation: engine refusal reaches Add without a false success or repeated lifecycle command. */
    @Test fun anUnopenedHashFailureExplainsStorageRecoveryOnTheActualAddResult() {
        server.activationBlocked = true
        show(mutableStateOf(null), mutableStateOf("/srv/blocked"))
        reveal(ChunksSemantics.ADD_SUBMIT)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
        rule.waitUntil(15_000) {
            runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                hasText("could not open or hash-check", substring = true)) }.isSuccess
        }
        rule.onNodeWithText("Start, Stop and Recheck cannot repair unavailable storage", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-add-storage-refusal.png")
        assertEquals(1, server.loads().size)
        assertTrue("Diagnosis must not repair/retry by issuing lifecycle writes",
            server.methods.none { it in setOf("d.open", "d.start", "d.resume", "d.erase") })
    }

    @Test fun anAbsoluteServerDefaultIsUsedAndAnExplicitFolderIsNeverOverwrittenByLateDiscovery() {
        server.directoryDefault = "/srv/default"
        val saved = mutableStateOf<String?>(null)
        val destination = mutableStateOf("/srv/chosen")
        show(saved, destination)
        rule.waitUntil(15_000) { server.methods.contains("directory.default") }
        // The fixture receiving the read is not the screen having APPLIED its
        // reply. Reset appears only once the discovered default is applied, so
        // wait for it: only then does "never overwritten" test late discovery.
        rule.waitUntil(15_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Reset to rTorrent default folder"))
            }.isSuccess
        }
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
