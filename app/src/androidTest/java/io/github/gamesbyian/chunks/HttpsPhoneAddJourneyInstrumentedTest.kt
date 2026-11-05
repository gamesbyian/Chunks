package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("chunks_https_phone_stage", value) })
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
    private fun await(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 90_000_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("HTTPS phone journey: $what")
            Thread.sleep(150)
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
        rule.onNodeWithText(text).performClick()
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
                rule.waitUntil(30_000) { visible("On this phone (experimental)") }
                stage("phone-open")
                click("Add torrent on this phone")
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                click("Change download location")
                click("Chunks app folder")
                type("Optional phone download subfolder", folder)
                type("Or paste a magnet or torrent link", url)
                // Leaving the phone screen must not turn an explicit one-time root
                // into the saved default when the same draft returns.
                stage("override-set")
                click("All environments…")
                rule.waitUntil(30_000) { visible("All environments") }
                click("Back")
                rule.waitUntil(30_000) { visible("Saves to: Chunks app folder") }
                rule.onNodeWithText("For this Add only. Saving a phone default is a separate action.").assertExists()
                rule.onNodeWithText("Optional phone download subfolder").assertTextContains(folder)
                stage("before-recreate")
                scenario.recreate()
                stage("after-recreate")
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("Saves to: Chunks app folder") }
                rule.onNodeWithText("For this Add only. Saving a phone default is a separate action.").assertExists()
                rule.onNodeWithText("Optional phone download subfolder").assertTextContains(folder)
                // Exercise a real refused retrieval and recover the same scoped draft.
                type("Or paste a magnet or torrent link", "https://127.0.0.1/blocked.torrent")
                click("Get torrent from link")
                rule.waitUntil(30_000) { visible(TorrentLinkResult.LOCAL_NETWORK) }
                assertNull(LocalEngineHolder.current()?.status(preview.infoHashV1))
                rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                stage("refusal-recovered")
                // Source URLs stay out of instance state by the existing privacy contract.
                type("Or paste a magnet or torrent link", url)
                stage("before-fetch")
                click("Get torrent from link")
                rule.waitUntil(45_000) { visible("Review download") }
                assertNull(LocalEngineHolder.current()?.status(preview.infoHashV1))
                assertFalse(destination.exists())
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                stage("fetched-review")
                click("Start downloading after Add (recommended)")
                click("Add stopped")
                await("exact stopped Add") { LocalEngineHolder.current()?.status(preview.infoHashV1)?.stopped == true }
                val phone = requireNotNull(LocalEngineHolder.current())
                previousLimits = phone.rateLimits()
                phone.setRateLimits(LocalRateLimits(downloadBytesPerSecond = 128 * 1024))
                assertEquals(destination.canonicalPath, phone.status(preview.infoHashV1)?.savePath)
                assertEquals(0L, phone.status(preview.infoHashV1)?.doneBytes)
                stage("added-stopped")
                click("Start")
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                    val allow = androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(
                        ".*:id/permission_allow_button"))
                    val button = device.wait(androidx.test.uiautomator.Until.findObject(allow), 20_000)
                    assertNotNull("Expected Android's notification permission prompt", button)
                    assertTrue("Start must wait until Android's notification choice is resolved",
                        phone.status(preview.infoHashV1)?.stopped == true)
                    button.click()
                    assertTrue("Notification prompt must dismiss before app controls are used",
                        device.wait(androidx.test.uiautomator.Until.gone(allow), 10_000))
                    assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
                    stage("notification-allowed")
                }
                await("production Start after notification choice") {
                    phone.status(preview.infoHashV1)?.let { !it.stopped && it.state != LocalTransferState.CHECKING } == true
                }
                assertTrue(phone.connectPeer(preview.infoHashV1, InetSocketAddress("127.0.0.1", seed.listenPort())))
                await("actual peer bytes and verified piece progression") {
                    phone.status(preview.infoHashV1)?.let { it.doneBytes >= piece.size && it.payloadDownloadedBytes > 0 } == true
                }
                stage("pieces-received")
                assertFalse("An Android permission prompt cannot cover the Stop action",
                    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).hasObject(
                        androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))))
                click("Stop")
                await("production Stop") { phone.status(preview.infoHashV1)?.stopped == true }
                stage("stopped-observed")
                // A paused flag precedes asynchronous disk-cache settlement. Use
                // the production post-Stop FLUSH_DISK_CACHE checkpoint before an
                // independent pathname read, as the existing intake journey does.
                assertTrue("Post-Stop disk checkpoint was not confirmed",
                    phone.saveResumeDataFor(preview.infoHashV1, 30_000))
                stage("checkpoint-confirmed")
                val target = File(destination, preview.name)
                await("independent piece byte readback") {
                    target.isFile && target.inputStream().use { it.readNBytes(piece.size) }.contentEquals(piece)
                }
                val status = requireNotNull(phone.status(preview.infoHashV1))
                assertTrue(status.doneBytes in 1 until preview.sizeBytes)
                assertEquals(public.place.kind.key, prefs.getString("default-kind", null))
                stage("phone-open")
                click("Add torrent on this phone")
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                stage("before-recreate")
                scenario.recreate()
                stage("after-recreate")
                scenario.onActivity { activity = it }
                rule.waitUntil(30_000) { visible("Saves to: Downloads/Chunks Downloads") }
                android.util.Log.i("ChunksHttps", "CHUNKS_HTTPS_PHONE verified=true received=${status.payloadDownloadedBytes} " +
                    "pieces_bytes=${status.doneBytes} complete=false default_preserved=true " +
                    "piece_sha256=" + MessageDigest.getInstance("SHA-256").digest(piece).joinToString("") { "%02x".format(it) })
                click("Hide phone Add options")
                stage("next-default-verified")
                click("Remove…")
                click("Remove transfer, keep data")
                await("exact session removal") { phone.status(preview.infoHashV1) == null }
                assertTrue(target.isFile)
            }
        } catch (failure: Throwable) {
            val frame = failure.stackTrace.firstOrNull { it.fileName == "HttpsPhoneAddJourneyInstrumentedTest.kt" }
            val kind = failure.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z]+")) } ?: "Failure"
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("chunks_https_phone_failure", "$lastStage-$kind-line" + (frame?.lineNumber ?: 0))
            })
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
