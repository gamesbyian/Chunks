package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installed app's REAL Add UI against the owner's development Seedhost:
 * connect, enter one unique synthetic magnet, keep the server's default Place
 * (or, when the server reports none, type a parent folder corroborated from
 * fresh reads of existing transfers; never a created folder), Review, Confirm
 * "add then start", and read the app's own outcome. An independent adapter
 * read then checks the exact hash, started state, directory and unchanged
 * unrelated inventory. Cleanup is one exact-hash Stop
 * and one session-only erase of that fixture; payload files are never touched
 * (a metadata-less magnet has none). No step is retried after an uncertain
 * reply. Skipped unless the protected main-only workflow authorizes it.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostAndroidAddUiInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun arg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
    private fun decoded(name: String): String? = arg(name)?.let {
        String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
    }

    /** Fixed labels only: never credentials, URLs, paths or server text. */
    private fun checkpoint(stage: String) {
        require(stage in STAGES)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2, Bundle().apply { putString("chunks_add_ui_stage", stage) })
    }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { stream ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, requireNotNull(stream)))
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
    }

    private fun scrollTo(text: String) =
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    private fun visible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private suspend fun findExact(
        remote: RTorrentRemote, hash: String, matches: (RemoteTransfer?) -> Boolean
    ): RemoteTransfer? {
        repeat(30) {
            val item = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
            if (matches(item)) return item
            delay(450)
        }
        return remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
    }

    /**
     * The development Seedhost reports no usable `directory.default`. Use the
     * most common parent of completed single-file transfers whose directory a
     * fresh exact read confirms, exactly as the scoped adapter test does.
     */
    private suspend fun corroboratedParent(remote: RTorrentRemote, inventory: List<RemoteTransfer>): String? {
        val probe = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val parents = inventory.asSequence()
            .filter { it.sizeBytes > 0 && it.completedBytes == it.sizeBytes }
            .take(48).toList()
            .mapNotNull { existing ->
                val path = existing.directory
                if (path.count { it == '/' } < 2 ||
                    runCatching { StoppedRemoteAddPlan.fromMagnet(probe, path) }.isFailure) null
                else {
                    val fresh = runCatching { remote.call("d.directory", listOf(existing.hash)) as? String }.getOrNull()
                    val multi = runCatching { remote.call("d.is_multi_file", listOf(existing.hash)) as? Number }.getOrNull()
                    path.takeIf { it == fresh && multi?.toInt() == 0 }
                }
            }
        return parents.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
    }

    @Test fun installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace() {
        assumeTrue("Protected explicit trusted-main Add UI journey only",
            arg("chunks_add_ui_once") == "authorized")
        val url = requireNotNull(decoded("chunks_live_url_b64"))
        val target = URI(url)
        require(target.scheme.equals("https", true) &&
            target.host.equals("ianseeds.wood.seedhost.eu", true) &&
            target.userInfo == null && target.rawQuery == null && target.rawFragment == null)
        val user = requireNotNull(decoded("chunks_live_user_b64"))
        val password = requireNotNull(decoded("chunks_live_pass_b64"))
        require(user.isNotBlank() && password.isNotEmpty())
        val nonce = requireNotNull(arg("chunks_unique_run"))
        require(nonce.matches(Regex("[0-9]{5,20}")))
        checkpoint("arguments")

        val name = "chunks-android-ui-$nonce"
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("Chunks synthetic Android Add UI acceptance $nonce".toByteArray())
            .joinToString("") { "%02x".format(it) }
        // The loopback tracker is unreachable from the server by design, so no
        // peer ever learns of this identity and no payload can arrive.
        val magnet = "magnet:?xt=urn:btih:$hash&dn=$name&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"

        val remote = RTorrentRemote(RemoteCredentials(url, user, password))
        val inventory = runBlocking { remote.listTransfers("main") }
        val before = inventory.map { it.hash.lowercase() }.toSet()
        checkpoint("preflight-inventory")
        val serverDefault = runBlocking { remote.defaultDirectory() }
        val parent = serverDefault ?: runBlocking { corroboratedParent(remote, inventory) }
        requireNotNull(parent) { "Neither a server default nor a corroborated parent; refusing the Add" }
        checkpoint(if (serverDefault != null) "preflight-default-place" else "preflight-custom-place")
        require(hash !in before && runBlocking { remote.lookupName(hash) } == null) {
            "Synthetic identity is not unique; refusing the Add"
        }
        checkpoint("preflight")

        var added = false
        try {
            rule.onNodeWithText("ruTorrent web URL or RPC address").performTextReplacement(url)
            rule.onNodeWithText("Username, if required").performTextReplacement(user)
            rule.onNodeWithText("Password").performTextReplacement(password)
            scrollTo("Connect to rTorrent")
            rule.onNodeWithText("Connect to rTorrent").performClick()
            try {
                rule.waitUntil(90_000) { visible("It's all here.") }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                checkpoint("connect-failed")
                throw AssertionError("The installed app did not reach the real server queue.")
            }
            rule.onNodeWithText("Password").assertDoesNotExist()
            checkpoint("queue")

            rule.onNodeWithText("+ Add torrent").performClick()
            rule.waitUntil(30_000) { visible("A place for every download.") }
            checkpoint("add-open")
            try {
                rule.onNodeWithText("Magnet link").performTextReplacement(magnet)
                checkpoint("magnet-entered")
                // The Add screen is a LazyColumn: the Place status line under the
                // folder field is not composed until scrolled into view.
                scrollTo("Custom server folder (optional)")
                rule.waitUntil(30_000) {
                    visible("Using rTorrent default Place:") || visible("No usable default Place was reported.")
                }
                // The app must agree with the preflight about the server default.
                assertEquals(serverDefault != null, visible("Using rTorrent default Place:"))
                checkpoint("place-status")
                if (serverDefault == null) {
                    rule.onNodeWithText("Custom server folder (optional)").performTextReplacement(parent)
                    rule.waitUntil(10_000) { visible("Using custom Place.") }
                }
            } catch (failure: Throwable) {
                // Post-login Add screen only (no credential is on screen).
                runCatching { capture("seedhost-android-add-ui-draft-failure") }
                throw failure
            }
            checkpoint("add-draft")

            scrollTo("Review Add")
            rule.onNodeWithText("Review Add").performClick()
            scrollTo("Confirm one server operation")
            assertTrue(visible("Infohash: $hash"))
            assertTrue(visible(if (serverDefault != null) "(server default; no directory override)"
                else "(custom parent folder)"))
            capture("seedhost-android-add-ui-review")
            checkpoint("review")

            scrollTo("Confirm: add then start")
            added = true
            rule.onNodeWithText("Confirm: add then start").performClick()
            checkpoint("confirmed")
            rule.waitUntil(120_000) { visible("Operation outcome") }
            scrollTo("Operation outcome")
            capture("seedhost-android-add-ui-outcome")
            if (!visible("Verified: added and started on rTorrent.")) {
                checkpoint("outcome-not-verified")
                throw AssertionError("The app did not report a verified Add and Start; no retry.")
            }
            checkpoint("app-verified")

            runBlocking {
                val readback = findExact(remote, hash) { it?.started == true }
                assertEquals("Independent read: exact hash started", true, readback?.started)
                assertEquals("Independent read: reviewed Place", parent, readback?.directory)
                assertEquals("Unrelated inventory identities changed", before + hash,
                    remote.listTransfers("main").map { it.hash.lowercase() }.toSet())
            }
            checkpoint("independent-readback")
        } finally {
            // Clean up ONLY the identity this run created, once each, from a
            // fresh read. Never Stop/erase after an uncertain reply blindly.
            if (added) runBlocking {
                val owned = runCatching {
                    remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                }.getOrNull()
                if (owned != null) {
                    if (owned.started) {
                        val stop = remote.setStarted(hash, false)
                        assertFalse("Stop uncertain; reconcile before any retry",
                            stop is LifecycleOutcome.Uncertain)
                    }
                    val fresh = findExact(remote, hash) { it?.started == false }
                    requireNotNull(fresh) { "Fixture not observed stopped; erase withheld" }
                    val erase = remote.eraseFromSession(hash, fresh.name, fresh.directory)
                    assertTrue("Exact synthetic session erase unverified",
                        erase is SessionEraseOutcome.VerifiedRemoved ||
                            erase is SessionEraseOutcome.PendingRemoval ||
                            erase is SessionEraseOutcome.AlreadyAbsent)
                    checkpoint("erase-attempted")
                }
            }
        }
        runBlocking {
            assertNull("Synthetic session remains after exact erase", findExact(remote, hash) { it == null })
            assertEquals("Unrelated inventory identities changed", before,
                remote.listTransfers("main").map { it.hash.lowercase() }.toSet())
        }
        checkpoint("inventory-restored")
    }

    private companion object {
        val STAGES = setOf("arguments", "preflight-inventory", "preflight-default-place",
            "preflight-custom-place", "preflight", "connect-failed", "queue", "add-open",
            "magnet-entered", "place-status", "add-draft",
            "review", "confirmed", "outcome-not-verified", "app-verified",
            "independent-readback", "erase-attempted", "inventory-restored")
    }
}
