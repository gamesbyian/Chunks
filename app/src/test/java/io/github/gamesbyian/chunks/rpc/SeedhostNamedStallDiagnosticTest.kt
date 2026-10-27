package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two user-reported stalled items; READ-ONLY diagnostics on the owner's
 * development Seedhost. No hashes, names, paths, tracker URLs, peer addresses,
 * passkeys, or raw fault messages leave protected CI.
 */
class SeedhostNamedStallDiagnosticTest {
    private fun remote(): RTorrentRemote {
        val url = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        assumeTrue("Only trusted protected read-only workflow", url.isNotBlank())
        val uri = URI(url)
        require(uri.scheme.equals("https", true) &&
            uri.host.equals("ianseeds.wood.seedhost.eu", true) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotBlank() && pass.isNotBlank())
        return RTorrentRemote(RemoteCredentials(url, user, pass))
    }

    private fun safe(label: String, line: String) {
        require(label in listOf("uncle-buck", "marshals"))
        // Only fixed names/booleans/nonnegative counts/verdicts, no raw input.
        val allowed = Regex("[A-Za-z0-9 _=.,:-]{1,380}")
        require(allowed.matches(line))
        println("CHUNKS_NAMED: $label $line")
    }

    @Test fun assessTwoReportedNamesWithoutChangingServer() = runBlocking {
        val rtorrent = remote()
        val inventory = rtorrent.listTransfers("main")
        val selectors = listOf(
            "uncle-buck" to "unclebuck2016s01e05",
            "marshals" to "marshalss01e07familybusiness"
        )
        val normalize = { name: String ->
            name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        }
        for ((label, token) in selectors) {
            val matches = inventory.filter { normalize(it.name).contains(token) }
            if (matches.size != 1) {
                safe(label, if (matches.isEmpty()) "match=not-found" else "match=ambiguous")
                continue
            }
            val item = matches.single()
            val hash = item.hash
            val name = item.name
            // Every optional diagnostic is best-effort, not a mutation trigger.
            val activity = try { rtorrent.readActivity(hash) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            val tracker = try { rtorrent.readTrackerHealth(hash) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            val fileSummary = try {
                val pre = rtorrent.call("d.hash", listOf(hash)) as? String
                require(pre?.equals(hash, true) == true)
                val files = rtorrent.listFiles(hash)
                val post = rtorrent.call("d.hash", listOf(hash)) as? String
                require(post?.equals(hash, true) == true)
                files.size to files.count { it.priority > 0 && it.sizeBytes > 0L }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { null }
            val bool = { value: Boolean? -> when (value) {
                true -> "yes"
                false -> "no"
                null -> "unknown"
            } }
            safe(label, "match=unique started=${bool(item.started)} " +
                "complete=${bool(item.sizeBytes > 0 && item.completedBytes >= item.sizeBytes)} " +
                "active=${bool(activity?.active)} open=${bool(activity?.open)} " +
                "peers=${activity?.connectedPeers ?: -1L} " +
                "downRatePositive=${bool(activity?.downloadBytesPerSecond?.let { it > 0 })} " +
                "files=${fileSummary?.first ?: -1} selectedFiles=${fileSummary?.second ?: -1} " +
                "trackers=${tracker?.total ?: -1} trackerEnabled=${tracker?.enabled ?: -1} " +
                "announceSuccess=${bool(tracker?.successfulAnnounces?.let { it > 0 })} " +
                "announcedPeers=${bool(tracker?.maxAnnouncedPeers?.let { it > 0 })}")
            val samples = ArrayList<RemoteTrafficSample>(3)
            var failed = false
            repeat(3) { index ->
                if (index > 0) delay(3_000L)
                if (!failed) {
                    try { samples.add(rtorrent.readTrafficSample(hash)) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                }
            }
            val evidence = if (!failed && samples.size == 3) {
                try { RemoteTrafficWindow.evaluate(samples) }
                catch (_: Exception) { null }
            } else null
            val stillExact = try {
                val current = rtorrent.listTransfers("main").filter { it.hash.equals(hash, true) }
                current.size == 1 && current.single().name == name
            } catch (_: Exception) { false }
            safe(label, "identityStable=${bool(stillExact)} " +
                "sample=${evidence?.verdict?.name ?: "UNAVAILABLE"} " +
                "receivedGrowth=${bool(evidence?.receivedDelta?.let { it > 0L })} " +
                "verifiedGrowth=${bool(evidence?.verifiedDelta?.let { it > 0L })} " +
                "peakPeers=${evidence?.peakPeers ?: -1L}")
            assertTrue("Only one exact named transfer inspected per selector", matches.size == 1)
        }
    }

    @Test fun filenameTokensMatchDotsAndReleaseSeparators() {
        val compact = { s: String ->
            s.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        }
        assertTrue(compact("Uncle.Buck.2016.S01E05.1080p.AMZN.WEBRip.DD5.1.x264-CasStudio")
            .contains("unclebuck2016s01e05"))
        assertTrue(compact("Marshals.S01E07.Family.Business.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb")
            .contains("marshalss01e07familybusiness"))
    }
}
