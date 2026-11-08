package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.*
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production HTTPS fetch/review/Add/Start with a real independently seeded publisher piece.
 * Opt-in disposable emulator only; reuses the installed-rTorrent campaign's public fixture host.
 * The downloader never receives a copied payload. This proves bounded pieces, not full ISO completion. */
@RunWith(AndroidJUnit4::class)
class HttpsPhoneAddJourneyInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var activity: MainActivity? = null
    private var lastStage = "initial"
    private fun stage(value: String) {
        require(value.matches(Regex("[a-z-]+")))
        lastStage = value
        android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE_STAGE $value")
    }
    private fun read(path: String, limit: Int): ByteArray {
        require(path in setOf("/ready", "/magnet3.torrent", "/magnet3.seed.torrent", "/magnet3.piece"))
        val connection = URL("http://127.0.0.1:18081$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000; connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200)
            return connection.inputStream.use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) }
        } finally { connection.disconnect() }
    }
    private fun await(what: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.nanoTime() + 90_000_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("HTTPS phone journey: $what" + runCatching { detail() }.getOrDefault("").let { if (it.isEmpty()) "" else " ($it)" })
            Thread.sleep(150)
        }
    }
    // User actions enqueue work with the v2 StandardTestDispatcher. Unlike
    // the independent peer/disk await() above, this wait advances the Compose
    // clock so an Add, Stop or Remove command is actually dispatched.
    private fun awaitUi(what: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(90_000, condition)
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("HTTPS phone journey: $what", timeout)
        }
    }
    private fun visible(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(text: String) {
        if (runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
        if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performScrollTo()
    }
    private fun click(text: String) {
        if (text == "Stop") stage("stop-lookup")
        rule.waitUntil(30_000) { runCatching {
            scroll(text); rule.onNodeWithText(text).assertIsEnabled(); true
        }.getOrDefault(false) }
        if (text == "Stop") stage("stop-ready")
        rule.onNodeWithText(text).performScrollTo()
        if (text == "Stop") stage("stop-visible")
        // A semantic click cannot miss when the list re-lays out between lookup and touch
        // injection (a lost Start tap left no permission prompt). Touch only if not clickable.
        val node = rule.onNodeWithText(text)
        if (androidx.compose.ui.semantics.SemanticsActions.OnClick in node.fetchSemanticsNode().config)
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        else node.performClick()
        if (text == "Stop") stage("stop-clicked")
    }
    private fun type(label: String, value: String) {
        scroll(label)
        rule.onNodeWithText(label).performTextReplacement(value)
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            manager.hideSoftInputFromWindow(activity?.window?.decorView?.windowToken, 0)
        }
        rule.onNodeWithText(label).performImeAction()
    }

    private fun awaitSourceReview() {
        try {
            rule.waitUntil(45_000) { visible("Review download") }
        } catch (failure: Throwable) {
            // Fixed labels only: source text, tracker tokens and provider errors
            // must never enter the public acceptance artifact.
            val refusals = listOf(
                "local-network" to TorrentLinkResult.LOCAL_NETWORK,
                "dns" to TorrentLinkResult.NO_HOST,
                "timeout" to TorrentLinkResult.TIMEOUT,
                "offline" to TorrentLinkResult.OFFLINE,
                "downgrade" to TorrentLinkResult.DOWNGRADE,
                "redirects" to TorrentLinkResult.TOO_MANY_REDIRECTS,
                "authorization" to TorrentLinkResult.REFUSED,
                "missing" to TorrentLinkResult.NOT_FOUND,
                "busy" to TorrentLinkResult.BUSY,
                "web-page" to TorrentLinkResult.WEB_PAGE,
                "size" to TorrentLinkResult.TOO_LARGE,
                "metainfo" to TorrentLinkResult.NOT_TORRENT,
                "response" to TorrentLinkResult.UNEXPECTED
            ).filter { (_, label) -> runCatching { visible(label) }.getOrDefault(false) }
                .joinToString(",") { it.first }.ifEmpty { "none" }
            fun shown(label: String) = runCatching { visible(label) }.getOrDefault(false)
            android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE_REVIEW_STATE " +
                "fetching=${shown("Getting torrent…")} ready=${shown("is ready to review below.")} " +
                "selected=${shown("Selected .torrent:")} refused=$refusals " +
                "form=${shown("Add a download on this phone")} " +
                "place=${shown("Saves to: Chunks app folder")}")
            throw failure
        }
    }

    @Test fun fetchedHttpsTorrentUsesOneTimePlaceThenNextAddUsesUnchangedDefault() {
        assumeTrue("Explicit assembled HTTPS acceptance only",
            InstrumentationRegistry.getArguments().getString("chunks_https_add") == "authorized")
        assertEquals("Disposable emulator required", "1", android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use { it.readText().trim() })
        if (Build.VERSION.SDK_INT == 29) LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        stage("permissions")
        val prefs = context.getSharedPreferences("local-download-place", Context.MODE_PRIVATE)
        val old = prefs.getString("default-kind", null)
        val oldRoot = prefs.getString("resolved-root", null)
        val choices = LocalDownloadPlaces.choices(context)
        val public = choices.single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val app = choices.single { it.place.kind == LocalPlaceKind.APP_STORAGE }
        assertTrue(public.verified); assertTrue(app.verified)
        assertTrue(LocalDownloadPlaces.remember(context, public))
        stage("places-verified")
        val raw = read("/magnet3.torrent", 2 * 1024 * 1024)
        val preview = TorrentMetainfoReader.preview(raw)
        assertEquals(1, preview.fileCount)
        val fixture = JSONObject(String(read("/ready", 65_536))).getJSONObject("magnet3")
        val url = fixture.getString("torrent_url")
        require(url.startsWith("https://") && url.endsWith(".torrent"))
        assertEquals(preview.infoHashV1, fixture.getString("hash"))
        val seedRaw = read("/magnet3.seed.torrent", 2 * 1024 * 1024)
        assertEquals(preview.infoHashV1, TorrentMetainfoReader.preview(seedRaw).infoHashV1)
        val piece = read("/magnet3.piece", 4 * 1024 * 1024)
        val folder = "https-once-" + System.nanoTime()
        val destination = File(app.place.root, folder)
        val seedRoot = File(context.cacheDir, folder).apply { mkdirs() }
        File(seedRoot, preview.name).writeBytes(piece)
        val seed = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true))
        seed.start()
        var previousLimits: LocalRateLimits? = null
        try {
            assertTrue(seed.addStopped(seedRaw, seedRoot) is LocalAddResult.AddedStopped)
            assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(preview.infoHashV1).outcome)
            await("independent original publisher piece recheck") {
                (seed.status(preview.infoHashV1)?.doneBytes ?: 0) >= piece.size
            }
            stage("seed-verified")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("This device") }
                stage("phone-open")
                click("+ Add")
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                click("Change download location")
                click("Chunks app folder")
                type("Optional phone download subfolder", folder)
                type("Or paste a magnet or torrent link", url)
                // Leaving the phone screen must not turn an explicit one-time root
                // into the saved default when the same draft returns.
                stage("override-set")
                click("Options")
                rule.waitUntil(30_000) { visible("Options · this device") }
                click("+ Add")
                rule.waitUntil(30_000) { visible("Saves to: Chunks app folder") }
                rule.onNodeWithText("For this Add only. Saving a phone default is a separate action.").assertExists()
                rule.onNodeWithText("Optional phone download subfolder").assertTextContains(folder)
                // Let the pending Compose/navigation frames settle before
                // Activity recreation. The v2 rule queues continuation work
                // instead of re-entering Android measure/layout synchronously.
                rule.waitForIdle()
                stage("before-recreate")
                scenario.recreate()
                stage("after-recreate")
                scenario.onActivity { activity = it }
                instrumentation.waitForIdleSync()
                rule.waitUntil(30_000) { visible("Saves to: Chunks app folder") }
                rule.onNodeWithText("For this Add only. Saving a phone default is a separate action.").assertExists()
                rule.onNodeWithText("Optional phone download subfolder").assertTextContains(folder)
                // A retained process-only Add owner must never turn recreation into an
                // implicit write before the person explicitly confirms this source.
                assertNull("Activity recreation cannot authorize a phone Add",
                    LocalEngineHolder.current()?.status(preview.infoHashV1))
                // Exercise a real refused retrieval and recover the same scoped draft.
                type("Or paste a magnet or torrent link", "https://127.0.0.1/blocked.torrent")
                click("Get torrent from link")
                // A refused URL's notice may be below the currently composed
                // LazyColumn viewport after recreation. Actually scroll to it:
                // keep requiring the precise private-network refusal, not just
                // an arbitrary error or absence of a transfer.
                rule.waitUntil(30_000) {
                    runCatching {
                        scroll(TorrentLinkResult.LOCAL_NETWORK)
                        visible(TorrentLinkResult.LOCAL_NETWORK)
                    }.getOrDefault(false)
                }
                assertNull(LocalEngineHolder.current()?.status(preview.infoHashV1))
                scroll("Saves to: Chunks app folder")
                rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                stage("refusal-recovered")
                // Source URLs stay out of instance state by the existing privacy contract.
                type("Or paste a magnet or torrent link", url)
                stage("before-fetch")
                click("Get torrent from link")
                awaitSourceReview()
                assertNull(LocalEngineHolder.current()?.status(preview.infoHashV1))
                assertFalse(destination.exists())
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                stage("fetched-review")
                // Cancelling this source review ends its one-time destination,
                // while leaving the saved default and native inventory alone.
                click("Cancel")
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                rule.onNodeWithText("Optional phone download subfolder").assert(
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                        androidx.compose.ui.text.AnnotatedString("")))
                assertNull(LocalEngineHolder.current()?.status(preview.infoHashV1))
                assertFalse(destination.exists())
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                click("Change download location")
                click("Chunks app folder")
                type("Optional phone download subfolder", folder)
                type("Or paste a magnet or torrent link", url)
                click("Get torrent from link")
                awaitSourceReview()
                stage("cancel-recovered")
                click("Start downloading after Add (recommended)")
                val mediaKey = io.github.gamesbyian.chunks.media.MediaTransferKey.of(
                    requireNotNull(phoneAddedKey(preview.infoHashV1)))
                fun excluded() = io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences(
                    File(context.filesDir, "media/automation.json")).isExcluded(mediaKey)
                assertFalse("Preview/cancel must not persist a transfer exclusion", excluded())
                click("Artwork & metadata")
                click("Automatically identify and fetch artwork & metadata")
                assertFalse("The draft choice alone is not Add admission", excluded())
                click("Add stopped")
                awaitUi("exact stopped Add") { LocalEngineHolder.current()?.status(preview.infoHashV1)?.stopped == true }
                assertTrue("Phone Add must persist the exclusion before exposing the job", excluded())
                val phone = requireNotNull(LocalEngineHolder.current())
                previousLimits = phone.rateLimits()
                phone.setRateLimits(LocalRateLimits(downloadBytesPerSecond = 128 * 1024))
                assertEquals(destination.canonicalPath, phone.status(preview.infoHashV1)?.savePath)
                assertEquals(0L, phone.status(preview.infoHashV1)?.doneBytes)
                // The in-test seed holds only piece 0 while the workflow's second real peer holds every
                // piece, so rarest-first fetches piece 0 *last*; at the 128 KiB/s cap it may not arrive
                // within the wait. Use the production "download in order" setting so it comes first.
                assertTrue("Sequential download readback", phone.setSequentialDownload(preview.infoHashV1, true))
                stage("added-stopped")
                click("Start")
                rule.waitForIdle() // Dispatch queued Start before checking the OS permission prompt.
                // Connect the verified piece-0 publisher immediately when Start is dispatched.
                // Android notification permission may take many seconds to resolve; delaying
                // the only independently verified first-piece peer until afterward lets the
                // full-source fixture compete for other pieces throughout that interval.
                // Peer connection is not a second Add/Start write or a retry.
                assertTrue("Independent publisher connection", phone.connectPeer(
                    preview.infoHashV1, InetSocketAddress("127.0.0.1", seed.listenPort())))
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                    val allow = androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(
                        ".*:id/permission_allow_button"))
                    // Observed (run 42098623009): an emulator "Pixel Launcher isn't responding" dialog
                    // covered the prompt. Another app's ANR is environment noise: choose Wait (never
                    // Close) and keep waiting. An ANR naming Chunks itself still fails.
                    var button: androidx.test.uiautomator.UiObject2? = null
                    val promptDeadline = System.currentTimeMillis() + 40_000
                    while (button == null && System.currentTimeMillis() < promptDeadline) {
                        button = device.wait(androidx.test.uiautomator.Until.findObject(allow), 5_000)
                        if (button != null) break
                        val anr = runCatching { device.findObjects(androidx.test.uiautomator.By.pkg("android"))
                            .mapNotNull { it.text }.firstOrNull { it.contains("isn't responding") } }.getOrNull()
                        if (anr != null) {
                            assertFalse("Chunks itself stopped responding: $anr", anr.contains("Chunks", ignoreCase = true))
                            val wait = device.findObject(androidx.test.uiautomator.By.res("android", "aerr_wait"))
                                ?: device.findObject(androidx.test.uiautomator.By.pkg("android").text("Wait"))
                            if (wait != null) {
                                wait.click(); stage("foreign-anr-waited")
                                android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE_FOREIGN_ANR " + anr.substringBefore(" isn't").take(40))
                            }
                        }
                    }
                    assertNotNull("Expected Android's notification permission prompt (foreground=" +
                        device.currentPackageName + " granted=" + (context.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) +
                        " engineStopped=" + LocalEngineHolder.current()?.status(preview.infoHashV1)?.stopped +
                        // A system ("android") window covering the prompt: name it (e.g. which app ANR'd).
                        " systemText=" + runCatching { device.findObjects(androidx.test.uiautomator.By.pkg("android"))
                            .mapNotNull { it.text?.take(80) }.filter { it.isNotBlank() }.distinct().take(6) }.getOrNull() + ")", button)
                    button!!.click()
                    assertTrue("Notification prompt must dismiss before app controls are used",
                        device.wait(androidx.test.uiautomator.Until.gone(allow), 10_000))
                    assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
                    stage("notification-allowed")
                }
                await("actual peer bytes and verified piece progression") {
                    phone.status(preview.infoHashV1)?.let { it.doneBytes >= piece.size && it.payloadDownloadedBytes > 0 } == true
                }
                // doneBytes counts *any* verified piece: the workflow's second real peer serves the
                // whole file, so other pieces can finish while piece 0 is still partial (observed:
                // done=22 MB, only 48 KiB of piece 0 on disk). Wait for piece 0 itself before Stop.
                val firstPieceFile = File(destination, preview.name)
                await("piece 0 itself on disk before Stop", detail = {
                    val head = if (firstPieceFile.isFile)
                        firstPieceFile.inputStream().use { it.readNBytes(piece.size) } else ByteArray(0)
                    val matchingPrefix = head.indices.firstOrNull { index -> head[index] != piece[index] }
                        ?: head.size
                    val observed = phone.status(preview.infoHashV1)
                    // Public disposable fixture only: no paths, URLs, names or private payload.
                    "file=${firstPieceFile.isFile} read=${head.size} expected=${piece.size} " +
                        "matchingPrefix=$matchingPrefix done=${observed?.doneBytes} " +
                        "downloaded=${observed?.payloadDownloadedBytes} " +
                        "stopped=${observed?.stopped}"
                }) {
                    firstPieceFile.isFile && firstPieceFile.inputStream().use { it.readNBytes(piece.size) }.contentEquals(piece)
                }
                stage("pieces-received")
                assertFalse("An Android permission prompt cannot cover the Stop action",
                    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).hasObject(
                        androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))))
                click("Stop")
                awaitUi("production Stop") { phone.status(preview.infoHashV1)?.stopped == true }
                stage("stopped-observed")
                // A paused flag precedes asynchronous disk-cache settlement. Use
                // the production post-Stop FLUSH_DISK_CACHE checkpoint before an
                // independent pathname read, as the existing intake journey does.
                assertTrue("Post-Stop disk checkpoint was not confirmed",
                    phone.saveResumeDataFor(preview.infoHashV1, 30_000))
                stage("checkpoint-confirmed")
                val target = File(destination, preview.name)
                // On timeout, say what was actually on disk: absent, short, zeros or different bytes.
                await("independent piece byte readback", detail = {
                    val head = if (target.isFile) target.inputStream().use { it.readNBytes(piece.size) } else ByteArray(0)
                    val matching = head.indices.firstOrNull { i -> i >= piece.size || head[i] != piece[i] } ?: head.size
                    val st = phone.status(preview.infoHashV1)
                    "exists=${target.exists()} file=${target.isFile} length=${target.length()} read=${head.size} " +
                        "matchingPrefix=$matching zeroHead=${head.isNotEmpty() && head.all { it == 0.toByte() }} " +
                        "done=${st?.doneBytes} progressPpm=${st?.progressPpm} stopped=${st?.stopped} piece=${piece.size}"
                }) {
                    target.isFile && target.inputStream().use { it.readNBytes(piece.size) }.contentEquals(piece)
                }
                val status = requireNotNull(phone.status(preview.infoHashV1))
                assertTrue(status.doneBytes in 1 until preview.sizeBytes)
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                // Ordinary phone Downloads -> Combined -> Details, using the
                // installed app's real observation of this exact local job.
                // Reading Details must not mutate the stopped native session.
                // This source is the only native job in the fresh, isolated
                // application. The list may legitimately display a renamed
                // alias; exact identity is verified in Details by infohash.
                assertEquals("Phone fixture requires one exact local job",
                    setOf(preview.infoHashV1.lowercase()),
                    phone.list().map { it.infoHash.lowercase() }.toSet())
                click("Transfers")
                rule.waitUntil(30_000) { visible("This device") }
                click("All environments")
                rule.waitUntil(30_000) { visible("All environments") }
                // The Combined list reads phone inventory and its privacy
                // policy asynchronously; entering the screen does not mean
                // its first full snapshot has been rendered. Wait for the
                // actual phone row to become scrollable, not just the title.
                val phoneRow = hasContentDescription("Expand ", substring = true) and
                    hasContentDescription(" on On this phone", substring = true)
                rule.waitUntil(45_000) {
                    runCatching {
                        if (visible("Show all transfers")) click("Show all transfers")
                        rule.onNode(hasScrollToIndexAction()).performScrollToNode(phoneRow)
                        true
                    }.getOrDefault(false)
                }
                rule.onNode(phoneRow).performClick()
                val details = hasContentDescription("All details for ", substring = true) and
                    hasContentDescription(" on On this phone", substring = true)
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(details)
                rule.onNode(details).performClick()
                rule.waitUntil(30_000) {
                    visible("Transfer details") && visible("Environment: On this phone")
                }
                rule.onNodeWithText("Show technical details").performScrollTo().performClick()
                assertTrue("Phone Details lost the exact observed hash",
                    visible("Exact infohash: ${preview.infoHashV1}"))
                assertTrue("Phone Details lost its exact one-time destination",
                    visible("Save folder reported by engine: ${destination.canonicalPath}"))
                assertTrue("Read-only Details must not restart the native job",
                    phone.status(preview.infoHashV1)?.stopped == true)
                click("Back to transfers")
                rule.waitUntil(30_000) { visible("All environments") }
                click("Transfers")
                rule.waitUntil(30_000) { visible("This device") }
                stage("phone-details-verified")
                stage("phone-open")
                click("+ Add")
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                stage("before-recreate")
                scenario.recreate()
                stage("after-recreate")
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE verified=true received=${status.payloadDownloadedBytes} " +
                    "pieces_bytes=${status.doneBytes} complete=false default_preserved=true " +
                    "piece_sha256=" + MessageDigest.getInstance("SHA-256").digest(piece).joinToString("") { "%02x".format(it) })
                click("Transfers")
                stage("next-default-verified")
                click("Remove…")
                click("Remove transfer, keep data")
                awaitUi("exact session removal") { phone.status(preview.infoHashV1) == null }
                assertTrue(target.isFile)
            }
        } catch (failure: Throwable) {
            val frame = failure.stackTrace.firstOrNull { it.fileName == "HttpsPhoneAddJourneyInstrumentedTest.kt" }
            val kind = failure.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z]+")) } ?: "Failure"
            android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE_FAILURE $lastStage-$kind-line" +
                (frame?.lineNumber ?: 0))
            throw failure
        } finally {
            stage("cleanup-entered")
            seed.close()
            LocalEngineHolder.current()?.let { phone ->
                previousLimits?.let { phone.setRateLimits(it) }
                if (phone.status(preview.infoHashV1) != null) {
                    phone.pause(preview.infoHashV1)
                    phone.removeKeepingData(preview.infoHashV1)
                }
            }
            // Keep the external payload after session removal. The disposable
            // emulator owns its lifetime; do not race FUSE/native descriptor teardown.
            seedRoot.deleteRecursively()
            prefs.edit().apply {
                if (old == null) remove("default-kind") else putString("default-kind", old)
                if (oldRoot == null) remove("resolved-root") else putString("resolved-root", oldRoot)
            }.commit()
            stage("cleanup-complete")
        }
    }
}
