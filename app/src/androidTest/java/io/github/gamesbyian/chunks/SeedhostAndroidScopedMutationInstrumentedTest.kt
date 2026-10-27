package io.github.gamesbyian.chunks

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An independently protected, one-shot real rTorrent mutation from an
 * INSTALLED Android app process. Never runs from ordinary PR instrumentation.
 * Uses one uniquely named tracker-bearing synthetic torrent, no real payload
 * deletion, never touches an existing unrelated hash, and never retries a
 * mutation after lost acknowledgement. This tests adapter/network, NOT a
 * user-operated Add/Start UI or physical Internet peer bytes.
 */
@RunWith(AndroidJUnit4::class)
class SeedhostAndroidScopedMutationInstrumentedTest {
    private fun arg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
    private fun decoded(name: String): String? = arg(name)?.let {
        String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun milestone(label: String) {
        require(label in setOf("authorized", "preflight", "loaded", "stopped-readback",
            "active-readback", "stopped-again", "erase-attempted", "inventory-restored"))
        InstrumentationRegistry.getInstrumentation().sendStatus(2,
            Bundle().apply { putString("chunks_mutation_stage", label) })
    }

    private fun synthetic(name: String): ByteArray {
        require(Regex("chunks-android-once-[0-9]{5,20}\\.txt").matches(name))
        val payload = "Chunks synthetic Android RPC acceptance\\n".toByteArray()
        val pieceHash = MessageDigest.getInstance("SHA-1").digest(payload)
        val announce = "http://127.0.0.1:1/announce"
        val prefix = "d8:announce${announce.length}:${announce}4:infod6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
        return prefix.toByteArray(Charsets.ISO_8859_1) + pieceHash +
            "ee".toByteArray(Charsets.ISO_8859_1)
    }

    private suspend fun findExact(
        remote: RTorrentRemote, hash: String, attempts: Int = 30,
        matches: (io.github.gamesbyian.chunks.rpc.RemoteTransfer?) -> Boolean
    ): io.github.gamesbyian.chunks.rpc.RemoteTransfer? {
        repeat(attempts) {
            val item = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
            if (matches(item)) return item
            delay(450)
        }
        return remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
    }

    @Test fun installedAndroidEngineCanMutateOnlyItsSyntheticHostFixture() = runBlocking {
        assumeTrue("Protected explicit trusted-main Android mutation only",
            arg("chunks_mutation_once") == "authorized")
        val raw = requireNotNull(decoded("chunks_live_url_b64"))
        val endpoint = URI(raw)
        require(endpoint.scheme.equals("https", true) &&
            endpoint.host.equals("ianseeds.wood.seedhost.eu", true) &&
            endpoint.userInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
        val user = requireNotNull(decoded("chunks_live_user_b64"))
        val pass = requireNotNull(decoded("chunks_live_pass_b64"))
        require(user.isNotBlank() && pass.isNotEmpty())
        val nonce = requireNotNull(arg("chunks_unique_run"))
        require(nonce.matches(Regex("[0-9]{5,20}")))
        val remote = RTorrentRemote(RemoteCredentials(raw, user, pass))
        milestone("authorized")
        // Existing-directory choice must be corroborated, not inferred from a
        // prefix or a missing directory.default. Do not create remote folders.
        val inventory = remote.listTransfers("main")
        val candidates = inventory.asSequence().filter {
            it.sizeBytes > 0 && it.completedBytes == it.sizeBytes
        }.take(48).toList()
        val probe = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val parents = candidates.mapNotNull { existing ->
            val path = existing.directory
            if (path.count { it == '/' } < 2 ||
                runCatching { StoppedRemoteAddPlan.fromMagnet(probe, path) }.isFailure) {
                null
            } else {
                val fresh = try { remote.call("d.directory", listOf(existing.hash)) as? String }
                    catch (_: Exception) { null }
                val multi = try { remote.call("d.is_multi_file", listOf(existing.hash)) as? Number }
                    catch (_: Exception) { null }
                path.takeIf { it == fresh && multi?.toInt() == 0 }
            }
        }
        val parent = parents.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        requireNotNull(parent) { "No fresh corroborated single-file parent, refusing all writes" }
        milestone("preflight")
        val name = "chunks-android-once-$nonce.txt"
        val bytes = synthetic(name)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val before = inventory.map { it.hash.lowercase() }.toSet()
        require(hash.lowercase() !in before && remote.lookupName(hash) == null) {
            "Synthetic identity is not unique; refusing mutation"
        }
        var eraseAttempted = false
        try {
            val add = remote.loadStoppedTorrentBytes(bytes, parent)
            milestone("loaded")
            // An uncertain RPC acknowledgment cannot authorize a new Add or
            // a Start. A fresh exact-hash read can independently verify state.
            assertFalse("Add outcome uncertain", add is RemoteAddOutcome.Uncertain)
            val stopped = findExact(remote, hash) { it != null && !it.started }
            requireNotNull(stopped)
            assertEquals(name, stopped.name)
            assertEquals(parent, stopped.directory)
            assertFalse(stopped.started)
            assertEquals(before + hash.lowercase(),
                remote.listTransfers("main").map { it.hash.lowercase() }.toSet())
            milestone("stopped-readback")

            val start = remote.setStarted(hash, true)
            assertFalse("Start acknowledgement uncertain; do not retry",
                start is LifecycleOutcome.Uncertain)
            val started = findExact(remote, hash) { it?.started == true }
            assertEquals(true, started?.started)
            var active = false
            repeat(25) {
                if (!active) {
                    active = remote.readActivity(hash).active == true
                    if (!active) delay(400)
                }
            }
            assertTrue("Synthetic session did not become active", active)
            milestone("active-readback")
            val stop = remote.setStarted(hash, false)
            assertFalse("Stop acknowledgement uncertain; do not retry",
                stop is LifecycleOutcome.Uncertain)
            val stoppedAgain = findExact(remote, hash) { it?.started == false }
            assertEquals(false, stoppedAgain?.started)
            milestone("stopped-again")
        } finally {
            // One bounded cleanup attempt is allowed only for the exact fixture
            // this test created, with fresh corroborated name/path. Never remove
            // backing payload bytes or any other transfer. No blind erase retry.
            val owned = try {
                remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                    ?.takeIf { it.name == name && it.directory == parent }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { null }
            if (owned != null && !eraseAttempted) {
                eraseAttempted = true
                val erase = remote.eraseFromSession(hash, name, parent)
                assertTrue("Exact synthetic session erase unverified",
                    erase is SessionEraseOutcome.VerifiedRemoved ||
                        erase is SessionEraseOutcome.PendingRemoval ||
                        erase is SessionEraseOutcome.AlreadyAbsent)
                milestone("erase-attempted")
            }
        }
        val missing = findExact(remote, hash) { it == null }
        assertNull("Synthetic session remains after exact erase", missing)
        assertEquals("Unrelated inventory identities changed", before,
            remote.listTransfers("main").map { it.hash.lowercase() }.toSet())
        milestone("inventory-restored")
    }
}
