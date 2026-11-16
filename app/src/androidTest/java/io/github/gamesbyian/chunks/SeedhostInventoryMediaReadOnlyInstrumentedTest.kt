package io.github.gamesbyian.chunks

import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.FileTransferMediaAssignmentStore
import io.github.gamesbyian.chunks.media.MediaAssignmentState
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROTECTED, READ-ONLY: automatic artwork for the owner's REAL primary Seedhost
 * inventory through the installed production UI, with the shipped default
 * provider set (owner-directed default ON). The app connects through its own
 * Connections form and opens Transfers; no Identify tap and no remote command
 * of any kind. Passes only when at least one existing remote transfer receives
 * an AUTOMATIC provider assignment, no provider request carries a hash, the
 * Seedhost host or the account name, and the inventory is unchanged.
 * Never reports names, paths, URLs or credentials: only fixed stages and counts.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostInventoryMediaReadOnlyInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    private fun decoded(key: String): String? = args.getString(key)?.let {
        String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun stage(name: String) {
        require(name in STAGES)
        instrumentation.sendStatus(2, Bundle().apply { putString("chunks_stage", name) })
    }

    private fun result(line: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("chunks_result", line) })
    }

    private fun waitOrStage(failStage: String, timeoutMs: Long, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            stage(failStage)
            throw AssertionError("Timed out at $failStage")
        }
    }

    private fun textVisible(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun typeInto(label: String, value: String): Boolean {
        repeat(5) {
            try {
                if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label, substring = true))
                rule.waitForIdle()
                rule.onNodeWithText(label).performTextReplacement(value)
                rule.waitForIdle()
                return true
            } catch (_: AssertionError) {
                Thread.sleep(400)
            }
        }
        return false
    }

    private class Observer(private val saved: ProxySelector?) : ProxySelector() {
        val uris: MutableList<URI> = Collections.synchronizedList(mutableListOf())
        override fun select(uri: URI): List<Proxy> {
            if (uri.scheme == "https") uris.add(uri)
            return saved?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            saved?.connectFailed(uri, sa, ioe)
        }
    }

    @Test fun realInventoryGetsAutomaticArtworkWithoutIdentifyOrMutation() {
        assumeTrue("Protected read-only real-inventory media proof only",
            args.getString("chunks_inventory_media") == "authorized")
        val url = requireNotNull(decoded("chunks_live_url_b64"))
        val user = requireNotNull(decoded("chunks_live_user_b64"))
        val password = requireNotNull(decoded("chunks_live_pass_b64"))
        val target = URI(url)
        require(target.scheme.equals("https", true) && target.host.equals(SEEDHOST, true) &&
            target.userInfo == null && target.rawQuery == null && target.rawFragment == null)
        val credentials = RemoteCredentials(url, user, password)
        val remote = RTorrentRemote(credentials)
        stage("authorized")

        val before = runBlocking { remote.listTransfers("main") }
        if (before.isEmpty()) { stage("inventory-empty"); throw AssertionError("Empty inventory") }
        val hashes = before.map { it.hash.lowercase() }.toSet()
        stage("inventory-read")

        // The store is cleared below and only this account is connected, so every
        // assignment for an inventory hash comes from this journey. Match by the
        // key's infohash: the app keys the profile by its verified (resolved) RPC
        // endpoint, which need not equal the ruTorrent URL typed here (run 43753355373).
        fun assignments() = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json"))
            .all().filter { it.key.value.substringAfterLast('|').lowercase() in hashes }

        val media = ChunksMedia.get(context)
        RememberedRemoteLogin(context).clear()
        RememberedRemoteProfileRoster(context).clearAll()
        runBlocking { media.clearAllMediaData().join() }
        // The shipped defaults: consent and automatic lookup on, default providers.
        media.updateSettings(MediaPrivacySettings())
        val original = ProxySelector.getDefault()
        val network = Observer(original)
        ProxySelector.setDefault(network)
        var automatic = 0
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                waitOrStage("launch-failed", 30_000) { textVisible("Connections") }
                val byDescription = rule.onAllNodes(hasContentDescription("Connections"))
                if (byDescription.fetchSemanticsNodes().isNotEmpty()) byDescription.onFirst().performClick()
                else rule.onAllNodesWithText("Connections").onFirst().performClick()
                rule.openConnectionFormIfNeeded(allowConnected = false)
                waitOrStage("form-missing", 30_000) {
                    textVisible("Seedbox hostname, ruTorrent URL or RPC address") ||
                        runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(
                            hasText("Seedbox hostname, ruTorrent URL or RPC address")); true }.getOrDefault(false)
                }
                if (!(typeInto("Connection name (optional)", "Wood") &&
                        typeInto("Seedbox hostname, ruTorrent URL or RPC address", url) &&
                        typeInto("Username, if required", user) && typeInto("Password", password))) {
                    stage("form-input-failed"); throw AssertionError("Could not enter a Connect field")
                }
                val connect = hasTestTag(ChunksSemantics.CONNECTION_CONNECT)
                waitOrStage("connect-control-missing", 20_000) {
                    runCatching {
                        rule.onNode(hasScrollToIndexAction()).performScrollToNode(connect)
                        rule.onNode(connect, useUnmergedTree = true).assertIsEnabled()
                        true
                    }.getOrDefault(false)
                }
                rule.waitForIdle()
                // Exactly one Connect dispatch; never retried.
                rule.onNode(connect, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
                waitOrStage("connect-failed", 90_000) { textVisible("Connection verified") }
                rule.onNodeWithText("Continue to Connections").performClick()
                waitOrStage("connections-missing", 30_000) {
                    runCatching {
                        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected", substring = true))
                        true
                    }.getOrDefault(false)
                }
                rule.onNodeWithTag(ChunksSemantics.CONNECTION_VIEW_TRANSFERS).performClick()
                waitOrStage("transfers-missing", 60_000) { textVisible("Search names or hashes") }
                stage("connected")

                // Merely observing the real inventory must identify something automatically.
                try {
                    rule.waitUntil(240_000) {
                        assignments().any { it.state == MediaAssignmentState.AUTOMATIC && it.match != null }
                    }
                } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                    // Allow the in-flight batch to settle so the counts below are informative.
                }
                Thread.sleep(15_000)
            }
        } finally {
            ProxySelector.setDefault(original)
        }

        val found = assignments()
        automatic = found.count { it.state == MediaAssignmentState.AUTOMATIC && it.match != null }
        val providers = found.mapNotNull { a -> a.match?.ref?.provider?.takeIf { a.state == MediaAssignmentState.AUTOMATIC } }
            .groupingBy { it }.eachCount().toSortedMap()
            .entries.joinToString(",") { (p, n) -> "${p.filter { it.isLetterOrDigit() }.take(16)}:$n" }
        val requests = synchronized(network.uris) { network.uris.toList() }
        val providerRequests = requests.filterNot { it.host.orEmpty().equals(SEEDHOST, true) }
        val hosts = providerRequests.groupingBy { uri ->
            val host = uri.host.orEmpty()
            PUBLIC_PROVIDER_DOMAINS.firstOrNull { host.equals(it, true) || host.endsWith(".$it", true) } ?: "other"
        }.eachCount().toSortedMap().entries.joinToString(",") { (h, n) -> "$h:$n" }
        val stored = FileTransferMediaAssignmentStore(File(context.filesDir, "media/assignments.json")).all().size
        val environments = found.map { it.key.value.substringBeforeLast('|') }.toSet().size
        result("inventory=${hashes.size} stored=$stored envs=$environments assigned=${found.size} automatic=$automatic " +
            "couldnt=${found.count { it.state == MediaAssignmentState.COULDNT_IDENTIFY }} " +
            "providers=${providers.ifEmpty { "none" }} hosts=${hosts.ifEmpty { "none" }}")

        // Provider requests never carry an infohash, the Seedhost host or the account name.
        val leaked = providerRequests.any { uri ->
            val text = java.net.URLDecoder.decode(uri.toString(), "UTF-8").lowercase()
            hashes.any { text.contains(it) } || text.contains(SEEDHOST) ||
                (user.length >= 3 && text.contains(user.lowercase()))
        }
        if (leaked) { stage("privacy-violation"); throw AssertionError("Provider request carried private data") }

        // Read-only: the same identities, names and started states as before.
        val after = runBlocking { remote.listTransfers("main") }
        val unchanged = before.size == after.size && before.all { b ->
            after.any { it.hash.equals(b.hash, true) && it.name == b.name && it.started == b.started }
        }
        if (!unchanged) { stage("inventory-changed"); throw AssertionError("Inventory changed during a read-only journey") }
        stage("unchanged")

        if (automatic == 0) { stage("no-automatic-assignment"); throw AssertionError("No automatic assignment") }
        stage("automatic-assignment")
    }

    private companion object {
        const val SEEDHOST = "ianseeds.wood.seedhost.eu"
        val PUBLIC_PROVIDER_DOMAINS = listOf("tvmaze.com", "themoviedb.org", "tmdb.org", "wikidata.org",
            "wikimedia.org", "musicbrainz.org", "coverartarchive.org", "archive.org", "openlibrary.org", "anilist.co")
        val STAGES = setOf("authorized", "inventory-empty", "inventory-read", "launch-failed", "form-missing",
            "form-input-failed", "connect-control-missing", "connect-failed", "connections-missing",
            "transfers-missing", "connected", "privacy-violation", "inventory-changed", "unchanged",
            "no-automatic-assignment", "automatic-assignment")
    }
}
