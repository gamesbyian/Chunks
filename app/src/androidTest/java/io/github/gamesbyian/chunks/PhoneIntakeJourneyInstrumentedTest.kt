package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun await(what: String, seconds: Long = 90, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            assertTrue("Timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(100)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

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

    private fun click(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsEnabled().performClick()

    @Test fun phaseA_externalReviewDownloadsThroughUiStopsThenProcessDies() {
        requirePhase("a")
        configurePermissions()
        assertNull("Harness must begin in a fresh process", LocalEngineHolder.current())
        assertTrue("Use a fresh disposable install", work.mkdir())
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
                waitForText("On this phone (experimental)")
                waitForText("Saves to: Downloads/Chunks Downloads")
                val target = File(publicRoot, name)
                assertFalse("Review cannot create payload", target.exists())
                assertNull("An external review must not create a native job", LocalEngineHolder.current())
                click(if (kind == "share") "Add and start downloading" else "Add magnet and start")
                await("real UI Add and peer download") {
                    LocalEngineHolder.current()?.status(hash)?.let {
                        it.metadataKnown && !it.stopped && it.progressPpm == 1_000_000
                    } == true
                }
                val phone = requireNotNull(LocalEngineHolder.current())
                assertEquals("Untouched default must be the public Downloads root",
                    publicRoot.canonicalPath, phone.status(hash)?.savePath)
                assertTrue(phone.flushCompletedForLibraryRead(hash, 30_000))
                assertArrayEquals(payload, target.readBytes())
                await("per-transfer real peer counters") {
                    (phone.status(hash)?.payloadDownloadedBytes ?: 0) >= payload.size &&
                        (seed.status(hash)?.payloadUploadedBytes ?: 0) >= payload.size
                }
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
        assertTrue("A killed stopped process cannot leave a running notification",
            notifications.activeNotifications.none { it.id == LocalEngineService.NOTIFICATION_ID })

        // Reopening the same external hash is still a review, not permission
        // to Add another job or resume the existing restored transfer.
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_VIEW,
            Uri.parse("magnet:?xt=urn:btih:$hash"))).use {
            waitForText("On this phone (experimental)")
            await("exact stopped restore") { LocalEngineHolder.current()?.status(hash)?.stopped == true }
            val phone = requireNotNull(LocalEngineHolder.current())
            assertTrue(phone.restoreProblems.toString(), phone.restoreProblems.isEmpty())
            assertEquals(listOf(hash), phone.completeIdentityInventory())
            waitForText("Start")
            val uploadedBefore = requireNotNull(phone.status(hash)).payloadUploadedBytes
            click("Start")
            await("restored verified seeding") { phone.status(hash)?.state == LocalTransferState.SEEDING }
            assertEquals(digest, sha(target.readBytes()))
            val receiverRoot = File(work, "fresh-receiver").apply { mkdir() }
            LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
                allowMultipleConnectionsPerIp = true)).use { receiver ->
                receiver.start()
                assertTrue(receiver.addStopped(File(work, "fixture.torrent").readBytes(), receiverRoot,
                    listOf(InetSocketAddress("127.0.0.1", phone.listenPort()))) is LocalAddResult.AddedStopped)
                assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(hash).outcome)
                await("fresh peer gets complete payload") { receiver.status(hash)?.progressPpm == 1_000_000 }
                assertTrue(receiver.flushCompletedForLibraryRead(hash, 30_000))
                assertArrayEquals(expected, File(receiverRoot, name).readBytes())
                await("restored transfer actually uploads") {
                    (phone.status(hash)?.payloadUploadedBytes ?: 0) - uploadedBefore >= expected.size &&
                        (receiver.status(hash)?.payloadDownloadedBytes ?: 0) >= expected.size
                }
            }
            click("Stop")
            await("second UI Stop") { phone.status(hash)?.stopped == true }
            click("Remove…")
            rule.onNodeWithText("Remove, keep files").assertIsEnabled().performClick()
            await("session-only removal") { phone.status(hash) == null }
            assertEquals("Remove must leave the downloaded payload", digest, sha(target.readBytes()))
            android.util.Log.i("ChunksIntake", "CHUNKS_INTAKE kind=$kind recovered stopped=true " +
                "bytes=${expected.size} seeded_bytes=${expected.size} kept_files=true sha256=$digest")
        }
        context.stopService(Intent(context, LocalEngineService::class.java))
        LocalEngineHolder.current()?.close() // joins native I/O before fixture-only cleanup
        assertTrue(target.delete())
        work.deleteRecursively()
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
