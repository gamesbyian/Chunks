package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RTorrentInstalledAddResponseFaultTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val appUrl = "http://127.0.0.1:18190/RPC2"
    private val credentials = RemoteCredentials("http://127.0.0.1:18090/RPC2", "chunks", "chunkspass")
    private val witness = RTorrentRemote(credentials)
    private val faultRelay = InstalledAddResponseFault(appUrl)
    private val journey get() = InstalledUniversalAddJourney(rule)

    private fun requireDisposableRunner() {
        assumeTrue("The loopback rTorrent runner must opt in",
            InstrumentationRegistry.getArguments().getString("chunks_rtorrent_add_fault") == "authorized")
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun stage(name: String) = instrumentation.sendStatus(0, Bundle().apply { putString("chunks_stage", name) })

    private fun enter(field: String, text: String) = LazyComposeActions.replaceText(rule, hasText(field), text)

    private fun connect() {
        journey.activate(ChunksSemantics.FOOTER_CONNECTIONS)
        rule.openConnectionFormIfNeeded(allowConnected = false)
        rule.openConnectionFormIfNeeded()
        enter("Seedbox hostname, ruTorrent URL or RPC address", appUrl)
        enter("Username, if required", credentials.username)
        enter("Password", credentials.password)
        LazyComposeActions.activateOnce(rule, hasTestTag(ChunksSemantics.CONNECTION_CONNECT), useUnmergedTree = true)
        rule.waitUntil(90_000) {
            rule.onAllNodes(hasText("Continue to Connections")).fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithText("Continue to Connections").performClick()
    }

    private fun fixture(name: String): TorrentMetainfoBuilder.Result {
        val payload = ByteArray(24 * 1024)
        return TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            listOf(TorrentMetainfoBuilder.Entry(listOf(name), payload.size.toLong()) { payload.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024))
    }

    private fun document(name: String, bytes: ByteArray): Uri {
        val tree = FixtureDocumentsProvider.treeUri(context)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver, root, "application/x-bittorrent", "$name.torrent"))
        requireNotNull(context.contentResolver.openOutputStream(document, "wt")).use { it.write(bytes) }
        return document
    }

    private fun launchMain(): MainActivity = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as MainActivity

    private fun openForStoppedAdd(activity: MainActivity, document: Uri, expectedHash: String): Intent? {
        var originalIntent: Intent? = null
        instrumentation.runOnMainSync {
            originalIntent = activity.intent
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "application/x-bittorrent"
                putExtra(Intent.EXTRA_STREAM, document)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasTestTag(ChunksSemantics.ADD_TARGET_REMOTE)).fetchSemanticsNodes().size == 1
        }
        journey.activate(ChunksSemantics.ADD_TARGET_REMOTE)
        val replace = hasText("Use new source") and hasClickAction()
        val reviewed = hasText("Infohash: $expectedHash", substring = true)
        rule.waitUntil(30_000) {
            rule.onAllNodes(replace).fetchSemanticsNodes().size == 1 || runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(reviewed)
                rule.onAllNodes(reviewed).fetchSemanticsNodes().size == 1
            }.getOrDefault(false)
        }
        if (rule.onAllNodes(replace).fetchSemanticsNodes().size == 1) LazyComposeActions.activateOnce(rule, replace)
        rule.waitUntil(30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(reviewed)
                rule.onAllNodes(reviewed).fetchSemanticsNodes().size == 1
            }.getOrDefault(false)
        }
        journey.chooseStopped()
        val archiveSummary = hasTestTag(ChunksSemantics.ADD_ORIGINAL_HANDLING_SUMMARY)
        rule.waitUntil(30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(archiveSummary)
                rule.onAllNodes(hasText(OriginalTorrentHandling.ARCHIVE.label, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        return originalIntent
    }

    private fun recreateAfterIntake(activity: MainActivity, originalIntent: Intent?): MainActivity {
        instrumentation.runOnMainSync {
            originalIntent?.let { activity.intent = it }
            activity.recreate()
        }
        // Single-task Share reentry changes the task's Intent and can strand
        // ActivityScenario's original launch monitor on its PAUSED identity.
        // Observe the actual replacement Activity from Android's lifecycle.
        var resumed: MainActivity? = null
        rule.waitUntil(30_000) {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull { it !== activity }
            }
            resumed != null
        }
        return requireNotNull(resumed)
    }

    private fun requireNoArchive(hash: String) {
        assertTrue("No archive is authorized without positive provenance",
            File(context.filesDir, SourceTorrentArchive.DIRECTORY).listFiles().orEmpty()
                .none { it.name.startsWith("$hash-") })
    }

    @Test fun stoppedDocumentAddRemainsUnattributedAfterTheRealServerReplyIsLost() {
        requireDisposableRunner()
        val exactScope = EnvironmentSnapshots.serverId(appUrl, credentials.username).value
        val directory = runBlocking { requireNotNull(witness.defaultDirectory()) }
        assertTrue(OriginalTorrentHandlingStore(context).setDefault(OriginalTorrentHandling.ARCHIVE))
        var activity = launchMain()
        try {
            connect()

            for (fault in listOf("drop", "timeout")) {
                val name = "chunks-rtorrent-fault-${System.nanoTime().toString(16)}.bin"
                val fixture = fixture(name)
                val hash = fixture.infoHashV1
                assertNull("The exact disposable fixture must be absent", runBlocking { witness.lookupName(hash) })
                val document = document(name, fixture.torrent)
                val originalIntent = openForStoppedAdd(activity, document, hash)
                faultRelay.arm(fault)
                journey.submit()
                stage("add-fault-$fault-dispatched-once")

                rule.waitUntil(45_000) {
                    runBlocking { witness.listTransfers("main").singleOrNull { it.hash.equals(hash, true) } }
                        ?.let { it.name == name && !it.started } == true
                }
                val hideFromMain = fault == "timeout"
                if (hideFromMain) runBlocking { witness.call("view.set_not_visible", listOf(hash, "main")) }
                try {
                    if (hideFromMain) {
                        assertEquals("The exact session still exists", name, runBlocking { witness.lookupName(hash) })
                        assertTrue("The job is absent from main while the app checks its exact session",
                            runBlocking { witness.listTransfers("main").none { it.hash.equals(hash, true) } })
                    }
                    journey.activate(ChunksSemantics.FOOTER_TRANSFERS)
                    journey.awaitTransferReceipt("Adding", "Add unverified", "Present, origin unverified")
                    activity = recreateAfterIntake(activity, originalIntent)
                    journey.awaitTransferReceipt("Adding", "Add unverified", "Present, origin unverified")
                    journey.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$exactScope")
                    journey.awaitAddReceipt("Present, origin unverified")
                    assertFalse("Exact readback cannot prove who created the registration", journey.hasReceiptState("Verified"))
                    val submit = hasTestTag(ChunksSemantics.ADD_SUBMIT)
                    val addList = rule.onNode(hasScrollToIndexAction())
                    addList.assertExists()
                    val missingSubmit = runCatching {
                        addList.performScrollToNode(submit)
                    }.exceptionOrNull()
                    assertTrue("The retained result has no Add action",
                        missingSubmit is AssertionError &&
                            missingSubmit.message?.contains("No node found that matches") == true)
                    journey.viewExactTransfer(exactScope, hash)
                    journey.awaitTransferReceipt("Present, origin unverified")

                    faultRelay.requireOneWriteAndNoStart()
                    if (hideFromMain) {
                        assertTrue("Read-only recovery did not change the server view",
                            runBlocking { witness.listTransfers("main").none { it.hash.equals(hash, true) } })
                        stage("add-fault-hidden-main-session-found-with-origin-unverified")
                    }
                } finally {
                    if (hideFromMain) runBlocking { witness.call("view.set_visible", listOf(hash, "main")) }
                }
                val observed = runBlocking { witness.listTransfers("main").single { it.hash.equals(hash, true) } }
                assertFalse("The fixture must remain stopped", observed.started)
                assertTrue("The job stayed in the exact reviewed folder",
                    directory.trimEnd('/') == observed.directory.trimEnd('/'))
                requireNoArchive(hash)
                assertArrayEquals("The original remains unchanged", fixture.torrent,
                    requireNotNull(context.contentResolver.openInputStream(document)).use { it.readBytes() })
                stage("add-fault-$fault-one-write-present-origin-unverified")
                journey.activate(ChunksSemantics.TRANSFERS_ADD_REVIEW + ".$exactScope")
                journey.activate(ChunksSemantics.ADD_ANOTHER)
                assertTrue(runBlocking { witness.eraseFromSession(hash, name, observed.directory) } is
                    SessionEraseOutcome.VerifiedRemoved)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private val processDeathName = "chunks-process-kill-during-write.bin"

    @Test fun submitThenKillTheAppProcess() {
        requireDisposableRunner()
        val fixture = fixture(processDeathName)
        val hash = fixture.infoHashV1
        assertNull("The fixture must be absent before the one Add", runBlocking { witness.lookupName(hash) })
        assertTrue(OriginalTorrentHandlingStore(context).setDefault(OriginalTorrentHandling.ARCHIVE))
        val document = document(processDeathName, fixture.torrent)
        val activity = launchMain()
        try {
            connect()
            openForStoppedAdd(activity, document, hash)
            faultRelay.arm("hold")
            journey.submit()
            rule.waitUntil(10_000) { faultRelay.hasWithheldReply() }
            journey.awaitAddReceipt("Adding")
            Log.i("ChunksAddFault", "process-death-real-write-awaits-ack")
            Process.killProcess(Process.myPid())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun freshProcessDoesNotReplayTheHeldAdd() {
        requireDisposableRunner()
        val fixture = fixture(processDeathName)
        val hash = fixture.infoHashV1
        val original = File(FixtureDocumentsProvider.baseDir(context), "$processDeathName.torrent")
        ActivityScenario.launch(MainActivity::class.java).use {
            journey.activate(ChunksSemantics.FOOTER_CONNECTIONS)
            journey.activate(ChunksSemantics.CONNECTION_VIEW_TRANSFERS, timeoutMillis = 75_000)
            rule.waitUntil(45_000) {
                val name = hasText(processDeathName, substring = true)
                runCatching {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(name)
                    rule.onAllNodes(name).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            rule.onNode(hasTestTag(ChunksSemantics.TRANSFERS_ADD_RECEIPT)).assertDoesNotExist()
            faultRelay.requireOneWriteAndNoStart()
            val observed = runBlocking { witness.listTransfers("main").single { it.hash.equals(hash, true) } }
            assertEquals(processDeathName, observed.name)
            assertFalse("The stopped job remained stopped through process loss", observed.started)
            requireNoArchive(hash)
            assertArrayEquals("The original document bytes remain unchanged", fixture.torrent, original.readBytes())
            assertTrue(runBlocking { witness.eraseFromSession(hash, observed.name, observed.directory) } is
                SessionEraseOutcome.VerifiedRemoved)
            stage("process-death-no-replay-no-start-original-unchanged")
        }
    }
}
