package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties
import java.util.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in, real two-process UI journey. tools/phone_intake_journey.sh installs
 * no fake engine: MainActivity and its actual Add/Stop/Start/Remove controls
 * manage the phone singleton. Only the seed, tracker and fresh receiver are
 * fixtures. Run in a disposable emulator, never against user downloads.
 */
@RunWith(AndroidJUnit4::class)
class PhoneIntakeJourneyInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val kind get() = arguments.getString("chunksIntakeKind").orEmpty()
    private val work get() = File(context.filesDir, "phone-intake-journey")
    private val marker get() = File(work, "receipt.properties")
    private val publicRoot get() = File(Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS), "Chunks Downloads")
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun requirePhase(phase: String) {
        assumeTrue("Run with tools/phone_intake_journey.sh", arguments.getString("chunksIntakePhase") == phase)
        assertTrue(kind in setOf("share", "magnet"))
    }

    private fun await(what: String, seconds: Long = 90, failure: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for $what; ${failure()}")
            Thread.sleep(100)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun assertPublicBytes(target: File, expected: ByteArray, phase: String) {
        val deadline = System.nanoTime() + 30_000_000_000L
        var failure: String
        do {
            val actual = try { target.readBytes() } catch (e: java.io.IOException) {
                failure = e.toString()
                if (System.nanoTime() >= deadline) fail("$phase: $failure; ${accessDetails(target)}")
                Thread.sleep(150)
                continue
            }
            if (actual.contentEquals(expected)) return
            failure = "read ${actual.size} bytes, sha256=${sha(actual)}"
            if (System.nanoTime() >= deadline) fail("$phase: $failure")
            Thread.sleep(150)
        } while (true)
    }

    private fun accessDetails(target: File): String {
        val rows = try {
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(target.name, "${Environment.DIRECTORY_DOWNLOADS}/Chunks Downloads/"), null).use { cursor ->
                buildList {
                    while (cursor?.moveToNext() == true) add(columns.indices.joinToString { cursor.getString(it).orEmpty() })
                }.toString()
            }
        } catch (e: Exception) { e.javaClass.simpleName }
        return "uid=${android.os.Process.myUid()} rootReadable=${publicRoot.canRead()} " +
            "fileReadable=${target.canRead()} size=${target.length()} mediaRows=$rows"
    }

    private fun configurePermissions() {
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT == 29)
            LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
    }

    private fun intent(action: String, uri: Uri? = null) = Intent(context, MainActivity::class.java).apply {
        this.action = action
        data = uri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String) {
        // The JNI Stop/Start postcondition can become visible before Compose
        // finishes the outstanding mutation and re-enables its action tiles.
        // Verify the *user-actionable* UI state, not merely the native state,
        // rather than racing a disabled Remove/Stop control.
        rule.waitUntil(30_000) {
            try {
                rule.onNodeWithText(text).assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        val node = rule.onNodeWithText(text)
        // Footer tabs live outside the scroll viewport. Never require a
        // Scroll SemanticsAction for an already-visible navigation control.
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsEnabled().performClick()
    }

    /** Footer Transfers opens the unified catalog. The explicit device
     * entry reaches the same native Start/Stop/Remove controls as before. */
    private fun enterDeviceControlsFromCatalog() {
        rule.waitUntil(30_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasText("Open device transfer controls"))
                true
            }.getOrDefault(false)
        }
        click("Open device transfer controls")
    }

    @Test fun phaseA_externalReviewDownloadsThroughUiStopsThenProcessDies() {
        requirePhase("a")
        configurePermissions()
        assertNull("Harness must begin in a fresh process", LocalEngineHolder.current())
        assertTrue("A previous intake fixture was not cleaned up", work.mkdir())
        val name = "chunks-intake-$kind-${System.nanoTime().toString(16)}.bin"
        val source = File(work, "source").apply { mkdir() }
        val payload = ByteArray(512 * 1024).also { Random(5569).nextBytes(it) }
        val sourceFile = File(source, name).apply { writeBytes(payload) }
        val seed = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true))
        seed.start()
        PhoneIntakeTracker().use { tracker ->
            val torrent = TorrentBuilder().path(sourceFile).pieceSize(16 * 1024)
                .flags(TorrentBuilder.V1_ONLY).addTracker(tracker.url).generate().entry().bencode()
            val hash = requireNotNull(TorrentMetainfoReader.preview(torrent).infoHashV1)
            val added = seed.addStopped(torrent, source)
            assertTrue(added.toString(), added is LocalAddResult.AddedStopped)
            assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(hash).outcome)
            await("fixture seed") { seed.status(hash)?.state == LocalTransferState.SEEDING }
            tracker.serve(hash, seed.listenPort())
            seed.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 64 * 1024))
            File(work, "fixture.torrent").writeBytes(torrent)
            val input = if (kind == "share") {
                val tree = FixtureDocumentsProvider.treeUri(context)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree))
                val document = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                    root, "application/x-bittorrent", "intake.torrent"))
                context.contentResolver.openOutputStream(document, "w").use {
                    requireNotNull(it).write(torrent)
                }
                intent(Intent.ACTION_SEND).apply {
                    type = "application/x-bittorrent"
                    putExtra(Intent.EXTRA_STREAM, document)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:$hash&tr=" +
                URLEncoder.encode(tracker.url, "UTF-8")))

            ActivityScenario.launch<MainActivity>(input).use {
                waitForText("Add on this device")
                waitForText("Saves to: Downloads/Chunks Downloads")
                val target = File(publicRoot, name)
                assertFalse("Review cannot create payload", target.exists())
                val reviewedEngine = LocalEngineHolder.current()
                assertTrue("External review cannot create jobs or inherit unrelated jobs",
                    reviewedEngine == null || reviewedEngine.completeIdentityInventory() == emptyList<String>())
                assertTrue("Saved-state discovery cannot hide failures",
                    reviewedEngine == null || reviewedEngine.restoreProblems.isEmpty())
                click(if (kind == "share") "Add and start downloading" else "Add magnet and start")
                // A tracker is a peer-discovery mechanism, not an essential
                // transport prerequisite for testing the installed Add flow.
                // Supply one deterministic, exact-hash localhost fixture peer
                // after the *real UI* has admitted and started the magnet,
                // just as the existing native manual-magnet tests do. This
                // avoids Android 15's nondeterministic tracker announce timing
                // while preserving actual metadata/peer pieces and byte proof.
                await("real UI registered and started the exact native job", 30, failure = {
                    "kind=$kind phone=${LocalEngineHolder.current()?.status(hash)}"
                }) {
                    LocalEngineHolder.current()?.status(hash)?.let {
                        !it.stopped && it.state != LocalTransferState.CHECKING
                    } == true
                }
                val phone = requireNotNull(LocalEngineHolder.current())
                if (kind == "magnet" && phone.status(hash)?.progressPpm != 1_000_000) {
                    assertTrue("Fixture seed has no native listener", seed.listenPort() in 1..65535)
                    assertTrue("Post-consent exact-hash magnet peer injection refused",
                        phone.connectPeer(hash, InetSocketAddress("127.0.0.1", seed.listenPort())))
                    android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE_PEER kind=magnet " +
                        "source=explicit_fixture_after_ui_consent seed_port=${seed.listenPort()}")
                }
                if (kind == "magnet") {
                    // Metadata and payload are separate libtorrent protocol
                    // phases. An upload-only magnet can exchange metadata,
                    // drop that initial peer, then become an active downloader
                    // with no connected peers. Only once its metadata has
                    // passed the native path survey and UPLOAD_MODE has cleared
                    // may the fixture establish a payload connection.
                    await("magnet metadata handoff into native payload mode", 45,
                        failure = { "phone=${phone.status(hash)} seed=${seed.status(hash)}" }) {
                        phone.status(hash)?.let {
                            it.metadataKnown && !it.stopped && !it.uploadOnly
                        } == true
                    }
                    val afterMetadata = requireNotNull(phone.status(hash))
                    if (afterMetadata.progressPpm != 1_000_000 && afterMetadata.peers == 0) {
                        assertTrue("Exact-hash payload peer after metadata transition refused",
                            phone.connectPeer(hash, InetSocketAddress("127.0.0.1", seed.listenPort())))
                        android.util.Log.i("ChunksIntake",
                            "CHUNKS_INTAKE_PEER kind=magnet source=payload_after_metadata " +
                                "seed_port=${seed.listenPort()}")
                    }
                }
                await("real UI Add and peer download", failure = {
                    "kind=$kind phone=${phone.status(hash)} seed=${seed.status(hash)}"
                }) {
                    phone.status(hash)?.let {
                        it.metadataKnown && !it.stopped && it.progressPpm == 1_000_000
                    } == true
                }
                assertEquals("Untouched default must be the public Downloads root",
                    publicRoot.canonicalPath, phone.status(hash)?.savePath)
                assertTrue(phone.flushCompletedForLibraryRead(hash, 30_000))
                // Native cache flush and scoped-storage visibility are distinct.
                // Require an independent exact read, bounded like the public Place fixture.
                assertPublicBytes(target, payload, "after real UI download")
                await("per-transfer real peer counters") {
                    (phone.status(hash)?.payloadDownloadedBytes ?: 0) >= payload.size &&
                        (seed.status(hash)?.payloadUploadedBytes ?: 0) >= payload.size
                }
                click("Transfers")
                enterDeviceControlsFromCatalog()
                waitForText("100% ·", substring = true)
                click("Stop")
                await("UI Stop readback") { phone.status(hash)?.stopped == true }
                waitForText("Start")
                await("foreground notification retired", 30) {
                    notifications.activeNotifications.none { it.id == LocalEngineService.NOTIFICATION_ID }
                }
                val receipt = Properties().apply {
                    setProperty("kind", kind)
                    setProperty("hash", hash)
                    setProperty("name", name)
                    setProperty("sha256", sha(payload))
                }
                marker.outputStream().use { receipt.store(it, "Synthetic intake fixture only") }
            }
            // Deliberately no saveResumeData() or app-engine close: exercise
            // what the user's actual Add/Stop path made durable itself.
            android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE kind=$kind armed reviewed=true " +
                "bytes=${payload.size} stopped=true sha256=${sha(payload)}")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Test fun phaseB_coldUiRestoreStartsSeedsToFreshPeerAndRemovesKeepingBytes() {
        requirePhase("b")
        assertNull("Phase B must be a new process", LocalEngineHolder.current())
        assertTrue("Phase A did not arm", marker.isFile)
        val receipt = Properties().apply { marker.inputStream().use { load(it) } }
        assertEquals(kind, receipt.getProperty("kind"))
        val hash = requireNotNull(receipt.getProperty("hash"))
        val name = requireNotNull(receipt.getProperty("name"))
        assertTrue(name.matches(Regex("chunks-intake-(share|magnet)-[0-9a-f]+\\.bin")))
        val expected = File(work, "source/$name").readBytes()
        val digest = sha(expected)
        assertEquals(receipt.getProperty("sha256"), digest)
        val target = File(publicRoot, name)
        assertEquals(publicRoot.canonicalPath, target.canonicalFile.parentFile?.canonicalPath)
        assertEquals(digest, sha(target.readBytes()))
        // The originating fixture seed died with phase A. Erase ONLY its
        // disposable test-private source before creating a second peer.
        // Otherwise a shared-user/relative-path alias to that complete file
        // could turn an apparent fresh download into local file reuse.
        val retiredFixtureSeed = File(work, "source/$name")
        assertTrue("Original fixture seeder data must be retired", retiredFixtureSeed.delete())
        assertFalse("No alternative original payload may survive", retiredFixtureSeed.exists())
        assertTrue("A killed stopped process cannot leave a running notification",
            notifications.activeNotifications.none { it.id == LocalEngineService.NOTIFICATION_ID })

        // Reopening the same external hash is still a review, not permission
        // to Add another job or resume the existing restored transfer.
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_VIEW,
            Uri.parse("magnet:?xt=urn:btih:$hash"))).use {
            waitForText("Add on this device")
            await("exact stopped restore") { LocalEngineHolder.current()?.status(hash)?.stopped == true }
            val phone = requireNotNull(LocalEngineHolder.current())
            assertTrue(phone.restoreProblems.toString(), phone.restoreProblems.isEmpty())
            assertEquals(listOf(hash), phone.completeIdentityInventory())
            click("Transfers")
            enterDeviceControlsFromCatalog()
            waitForText("Start")
            val uploadedBefore = requireNotNull(phone.status(hash)).payloadUploadedBytes
            click("Start")
            await("restored verified seeding", failure = {
                "hash=$hash status=${phone.status(hash)} listenPort=${phone.listenPort()} " +
                    "inventory=${phone.completeIdentityInventory()} restoreProblems=${phone.restoreProblems} " +
                    "publicBytes=${target.length()} expectedBytes=${expected.size}"
            }) { phone.status(hash)?.state == LocalTransferState.SEEDING }
            assertEquals(digest, sha(target.readBytes()))
            val receiverRoot = File(work, "fresh-receiver")
            assertTrue("Fresh receiver directory must be newly created", receiverRoot.mkdir())
            assertTrue("Fresh receiver must contain no cached/previous torrent bytes",
                receiverRoot.listFiles()?.isEmpty() == true)
            val receiveTarget = File(receiverRoot, name)
            assertFalse("Receiver cannot already own a completed payload", receiveTarget.exists())
            await("restored phone has a listening port", 20) { phone.listenPort() in 1..65535 }
            val seedPort = phone.listenPort()
            LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true)).use { receiver ->
                receiver.start()
                // A bounded fixture download makes counter and peer observations
                // reproducible on the emulator's otherwise near-instant loopback.
                receiver.setRateLimits(LocalRateLimits(downloadBytesPerSecond = 64 * 1024))
                val addedReceiver = receiver.addStopped(File(work, "fixture.torrent").readBytes(), receiverRoot,
                    listOf(InetSocketAddress("127.0.0.1", seedPort)))
                assertTrue("Actual native receiver Add: $addedReceiver",
                    addedReceiver is LocalAddResult.AddedStopped)
                val initial = requireNotNull(receiver.status(hash))
                android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE_FRESH kind=$kind " +
                    "initial=$initial port=$seedPort before_present=${receiveTarget.exists()} " +
                    "receiver_root=${receiverRoot.canonicalPath}")
                assertEquals("A new receiver cannot start with verified torrent pieces",
                    0L, initial.doneBytes)
                assertFalse("A newly admitted receiver must not already have complete bytes",
                    receiveTarget.exists())
                assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(hash).outcome)
                // add_torrent_params.peers may be consumed while a stopped job is
                // checking files. Inject the explicit fixture endpoint *after*
                // a confirmed Start, as the native engine's own peer tests do.
                await("fresh receiver ready for peer connection", 30, failure = {
                    "receiver=${receiver.status(hash)} phone=${phone.status(hash)}"
                }) {
                    receiver.status(hash)?.let {
                        !it.stopped && (it.state == LocalTransferState.DOWNLOADING ||
                            (it.state == LocalTransferState.SEEDING &&
                                it.payloadDownloadedBytes >= expected.size))
                    } == true
                }
                if ((receiver.status(hash)?.payloadDownloadedBytes ?: 0) < expected.size)
                    assertTrue("Exact-hash post-Start fixture peer connection refused",
                        receiver.connectPeer(hash, InetSocketAddress("127.0.0.1", seedPort)))
                // A successful native flush and independent file readback must
                // precede the payload-statistics witness, just as in the
                // original production-connected journey. Polling all-time
                // counters while disk hash/write completion is still pending
                // turned a previous working Share path into a false failure.
                await("fresh peer gets complete verified payload", failure = {
                    "port=$seedPort initial=$initial phone=${phone.status(hash)} " +
                        "receiver=${receiver.status(hash)}"
                }) { receiver.status(hash)?.progressPpm == 1_000_000 }
                assertTrue(receiver.flushCompletedForLibraryRead(hash, 30_000))
                assertArrayEquals(expected, receiveTarget.readBytes())
                assertFalse("Original fixture seed must not be recreated by receiver",
                    retiredFixtureSeed.exists())
                fun counterDetails() = "before=$uploadedBefore port=$seedPort initial=$initial " +
                    "phone=${phone.status(hash)} receiver=${receiver.status(hash)}"
                await("fresh peer's native payload counters prove transfer",
                    failure = { counterDetails() }) {
                    (phone.status(hash)?.payloadUploadedBytes ?: 0) - uploadedBefore >= expected.size &&
                        (receiver.status(hash)?.payloadDownloadedBytes ?: 0) >= expected.size
                }
                android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE_COUNTERS kind=$kind ${counterDetails()}")
            }
            click("Stop")
            await("second UI Stop") { phone.status(hash)?.stopped == true }
            click("Remove…")
            // Make the confirmation genuinely reachable to a user before
            // dispatching it. An enabled but offscreen semantics node alone
            // is weaker evidence than a scrolled, actionable control.
            click("Remove transfer, keep data")
            await("session-only removal", failure = {
                val messages = listOf(
                    "Session removal was not confirmed",
                    "A prior native action needs a fresh confirmed transfer list",
                    "Phone torrent status unavailable"
                ).filter { text ->
                    runCatching {
                        rule.onAllNodesWithText(text, substring = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }.getOrDefault(false)
                }
                "status=${phone.status(hash)} inventory=${phone.completeIdentityInventory()} " +
                    "problems=${phone.restoreProblems} visible_warnings=$messages"
            }) { phone.status(hash) == null }
            assertEquals("Remove must leave the downloaded payload", digest, sha(target.readBytes()))
            android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE kind=$kind recovered stopped=true " +
                "bytes=${expected.size} seeded_bytes=${expected.size} kept_files=true sha256=$digest")
        }
        context.stopService(Intent(context, LocalEngineService::class.java))
        LocalEngineHolder.current()?.close() // joins native I/O before fixture-only cleanup
        assertTrue(target.delete())
        work.deleteRecursively()
    }

    /**
     * L6 on the installed app: a real Share Add downloads from a fixture seed
     * into the default public Downloads Place, then the production card's
     * "Remove transfer and data" runs. Independent readback must show the
     * exact payload gone, the session gone and an unrelated canary in the
     * SAME folder byte-identical. Disposable emulator only.
     */
    @Test fun phaseDelete_realUiRemoveTransferAndDataDeletesOnlyExactPayload() {
        assumeTrue("Run with tools/phone_intake_journey.sh",
            arguments.getString("chunksIntakePhase") == "delete")
        configurePermissions()
        assertNull("Harness must begin in a fresh process", LocalEngineHolder.current())
        work.deleteRecursively()
        assertTrue(work.mkdir())
        val name = "chunks-intake-delete-${System.nanoTime().toString(16)}.bin"
        val source = File(work, "source").apply { mkdir() }
        val payload = ByteArray(512 * 1024).also { Random(7741).nextBytes(it) }
        val sourceFile = File(source, name).apply { writeBytes(payload) }
        publicRoot.mkdirs()
        val canary = File(publicRoot, "chunks-intake-canary-${System.nanoTime().toString(16)}.bin")
        val canaryBytes = ByteArray(4096).also { Random(99).nextBytes(it) }
        canary.writeBytes(canaryBytes)
        val seed = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true))
        seed.start()
        try {
            PhoneIntakeTracker().use { tracker ->
                val torrent = TorrentBuilder().path(sourceFile).pieceSize(16 * 1024)
                    .flags(TorrentBuilder.V1_ONLY).addTracker(tracker.url).generate().entry().bencode()
                val hash = requireNotNull(TorrentMetainfoReader.preview(torrent).infoHashV1)
                assertTrue(seed.addStopped(torrent, source) is LocalAddResult.AddedStopped)
                assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(hash).outcome)
                await("fixture seed") { seed.status(hash)?.state == LocalTransferState.SEEDING }
                tracker.serve(hash, seed.listenPort())
                val tree = FixtureDocumentsProvider.treeUri(context)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree))
                val document = requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                    root, "application/x-bittorrent", "delete.torrent"))
                context.contentResolver.openOutputStream(document, "w").use { requireNotNull(it).write(torrent) }
                val input = intent(Intent.ACTION_SEND).apply {
                    type = "application/x-bittorrent"
                    putExtra(Intent.EXTRA_STREAM, document)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val target = File(publicRoot, name)
                ActivityScenario.launch<MainActivity>(input).use {
                    waitForText("Saves to: Downloads/Chunks Downloads")
                    assertFalse("Review cannot create payload", target.exists())
                    click("Add and start downloading")
                    await("real UI registered the exact native job", 30) {
                        LocalEngineHolder.current()?.status(hash) != null
                    }
                    val phone = requireNotNull(LocalEngineHolder.current())
                    if (phone.status(hash)?.progressPpm != 1_000_000)
                        phone.connectPeer(hash, InetSocketAddress("127.0.0.1", seed.listenPort()))
                    await("real UI Add and peer download", failure = { "phone=${phone.status(hash)}" }) {
                        phone.status(hash)?.progressPpm == 1_000_000
                    }
                    assertTrue(phone.flushCompletedForLibraryRead(hash, 30_000))
                    assertPublicBytes(target, payload, "before delete-data")
                    // The Share review stays on Add; the verified job and
                    // lifecycle controls live only in device Transfers.
                    click("Transfers")
                    enterDeviceControlsFromCatalog()
                    waitForText("100% ·", substring = true)
                    click("Stop")
                    await("UI Stop readback") { phone.status(hash)?.stopped == true }
                    // L5 first: keep-data Remove, then re-add the SAME hash.
                    click("Remove…")
                    click("Remove transfer, keep data")
                    await("keep-data session removal") { phone.status(hash) == null }
                    assertPublicBytes(target, payload, "after keep-data Remove")
                }
                val phone = requireNotNull(LocalEngineHolder.current())
                var readded = false
                for (attempt in 1..6) {
                    ActivityScenario.launch<MainActivity>(input).use {
                        waitForText("Saves to: Downloads/Chunks Downloads")
                        click("Add and start downloading")
                        // A same-session re-add may be refused while native
                        // I/O of the old handle drains; that is a refusal,
                        // not an uncertain write, so a later user retry is valid.
                        await("re-add outcome", 60) {
                            phone.status(hash) != null || rule.onAllNodesWithText(
                                "previous Remove is still finishing", substring = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        if (phone.status(hash) == null) return@use
                        readded = true
                        android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE_READD attempt=$attempt")
                        await("re-added same hash rechecks kept bytes to 100%",
                            failure = { "phone=${phone.status(hash)}" }) {
                            phone.status(hash)?.progressPpm == 1_000_000
                        }
                        val reused = requireNotNull(phone.status(hash))
                        assertTrue("Re-add must reuse kept bytes, not download them again: $reused",
                            reused.payloadDownloadedBytes < payload.size)
                        assertPublicBytes(target, payload, "after same-hash re-add")
                        click("Transfers")
                        enterDeviceControlsFromCatalog()
                        waitForText("100% ·", substring = true)
                        click("Stop")
                        await("UI Stop readback after re-add") { phone.status(hash)?.stopped == true }
                        // L6 on the NEW session generation.
                        click("Remove…")
                        click("Remove transfer and data")
                        await("verified native deletion message", failure = {
                            "status=${phone.status(hash)} exists=${target.exists()}"
                        }) {
                            rule.onAllNodesWithText("Verified: the reviewed transfer was removed", substring = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        // Independent witnesses, not the app's own message.
                        await("session absent") { phone.status(hash) == null }
                        assertFalse("Exact payload must be gone", target.exists())
                        assertFalse("No payload name may remain in the folder",
                            publicRoot.list()?.contains(name) == true)
                        assertArrayEquals("Unrelated canary in the same folder must survive",
                            canaryBytes, canary.readBytes())
                        assertTrue("Fixture seed source is not the phone's payload and must survive",
                            sourceFile.readBytes().contentEquals(payload))
                        android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE_DELETE verified=true payload_absent=true " +
                            "session_absent=true canary_intact=true bytes=${payload.size}")
                    }
                    if (readded) break
                    Thread.sleep(3_000)
                }
                assertTrue("Same-hash re-add was never admitted", readded)
            }
        } finally {
            seed.close()
            context.stopService(Intent(context, LocalEngineService::class.java))
            LocalEngineHolder.current()?.close()
            canary.delete()
            work.deleteRecursively()
        }
    }
}

/** One loopback-only fixture tracker; never handles a real user's swarm. */
private class PhoneIntakeTracker : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/announce"
    fun serve(hash: String, port: Int) {
        Thread({
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = reader.readLine().orEmpty()
                    while (reader.readLine()?.takeIf { it.isNotEmpty() } != null) { }
                    val query = request.substringAfter(' ').substringBefore(' ').substringAfter('?', "")
                        .split('&').mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
                        .associate { it[0] to it[1] }
                    val observed = query["info_hash"]?.let {
                        java.net.URLDecoder.decode(it, "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
                    }?.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    val peers = if (observed == hash) byteArrayOf(127, 0, 0, 1,
                        (port shr 8).toByte(), port.toByte()) else byteArrayOf()
                    val body = "d8:intervali1e12:min intervali1e5:peers${peers.size}:".toByteArray() + peers + "e".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: Exception) { if (!server.isClosed) continue }
        }, "chunks-intake-tracker").apply { isDaemon = true; start() }
    }
    override fun close() = server.close()
}
