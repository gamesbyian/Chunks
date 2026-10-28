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
 * connect, enter one unique synthetic magnet, keep the server's default Place,
 * Review, Confirm "add then start", and read the app's own outcome. An
 * independent adapter read then checks the exact hash, started state, default
 * directory and unchanged unrelated inventory. Cleanup is one exact-hash Stop
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

    @Test fun installedAppAddsOneSyntheticMagnetAtTheServerDefaultPlace() {
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
        val (before, serverDefault) = runBlocking {
            remote.listTransfers("main").map { it.hash.lowercase() }.toSet() to remote.defaultDirectory()
        }
        requireNotNull(serverDefault) { "Server reports no default Place; refusing the Add" }
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
            rule.onNodeWithText("Magnet link").performTextReplacement(magnet)
            // The optional picker stays empty: the server's own default is used.
            rule.waitUntil(30_000) {
                visible("Using rTorrent default Place:") || visible("No usable default Place was reported.")
            }
            assertFalse("Server default Place unavailable", visible("No usable default Place was reported."))
            checkpoint("add-draft")

            scrollTo("Review Add")
            rule.onNodeWithText("Review Add").performClick()
            scrollTo("Confirm one server operation")
            assertTrue(visible("Infohash: $hash"))
            assertTrue(visible("(server default; no directory override)"))
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
                assertEquals("Independent read: server default Place", serverDefault, readback?.directory)
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
        val STAGES = setOf("arguments", "preflight", "connect-failed", "queue", "add-draft",
            "review", "confirmed", "outcome-not-verified", "app-verified",
            "independent-readback", "erase-attempted", "inventory-restored")
    }
}
