package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Owner-authorized acceptance against the designated development Seedhost only.
 * Runs solely from the manually dispatched trusted-main workflow with secrets;
 * ordinary PR builds skip it. Prints nonsecret evidence lines prefixed CHUNKS_LIVE.
 */
class SeedhostLiveAcceptanceTest {
    private fun remote(): RTorrentRemote {
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        assumeTrue("Only in the protected Seedhost workflow", rawUrl.isNotBlank())
        val url = URI(rawUrl)
        require(url.scheme.equals("https", true) && url.host?.endsWith(".seedhost.eu", true) == true &&
            url.userInfo == null && url.query == null && url.fragment == null) {
            "Only the credential-free HTTPS development Seedhost is accepted"
        }
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val password = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotBlank() && password.isNotEmpty())
        return RTorrentRemote(RemoteCredentials(rawUrl, user, password))
    }

    private fun log(line: String) = println("CHUNKS_LIVE: " + line.replace(Regex("[\\r\\n]"), " ").take(400))

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val value = block()
        log("$label took ${(System.nanoTime() - start) / 1_000_000} ms")
        return value
    }

    @Test fun readOnlyCompatibilityAgainstRealInventory() = runBlocking {
        val rtorrent = remote()
        val probe = timed("probe") { rtorrent.probe() }
        log("version=${probe.version}")
        val methods = probe.advertisedMethods
        if (methods == null) log("system.listMethods unavailable")
        else for (name in listOf("d.multicall2", "download_list", "system.multicall", "load.raw_verbose",
                "load.verbose", "d.erase", "d.start", "d.stop", "f.multicall", "f.priority.set",
                "d.update_priorities", "directory.default", "d.directory.set"))
            log("method $name advertised=${name in methods}")
        log("default directory present=${rtorrent.defaultDirectory() != null}")

        val fast = timed("d.multicall2 list") { rtorrent.listTransfers("main") }
        val paged = timed("paged download_list+system.multicall list") { rtorrent.listTransfersPaged("main") }
        assertEquals("Paged and multicall listings must name the same transfers in order",
            fast.map { it.hash.lowercase() }, paged.map { it.hash.lowercase() })
        assertEquals(fast.map { Triple(it.name, it.directory, it.sizeBytes) },
            paged.map { Triple(it.name, it.directory, it.sizeBytes) })

        // Each call is a fresh HTTPS exchange; repeated reads must agree.
        repeat(2) { attempt ->
            val again = timed("repeat list ${attempt + 1}") { rtorrent.listTransfers("main") }
            assertEquals(fast.map { it.hash.lowercase() }.toSet(), again.map { it.hash.lowercase() }.toSet())
        }

        val started = fast.count { it.started }
        val complete = fast.count { it.sizeBytes > 0 && it.completedBytes == it.sizeBytes }
        log("transfers=${fast.size} started=$started complete=$complete " +
            "totalBytes=${fast.sumOf { it.sizeBytes }} maxNameLength=${fast.maxOfOrNull { it.name.length } ?: 0}")
        log("rich fields present=${fast.all { it.downBytesPerSecond != null }}")

        var multiFile = 0
        for (transfer in fast.take(25)) {
            val files = rtorrent.listFiles(transfer.hash)
            assertTrue("Every transfer lists at least one file", files.isNotEmpty())
            assertTrue(files.all { it.path.isNotBlank() && it.sizeBytes >= 0 && it.priority in 0..2 })
            assertEquals("File sizes must add up to the transfer size for ${transfer.hash}",
                transfer.sizeBytes, files.sumOf { it.sizeBytes })
            if (files.size > 1) multiFile++
        }
        log("file listings checked=${minOf(25, fast.size)} multiFile=$multiFile")
        fast.take(5).forEach { log("sample name=\"${it.name}\" started=${it.started} complete=${it.completedBytes == it.sizeBytes}") }
    }

    /** Deepest absolute directory shared by all paths, at least two levels deep, else null. */
    private fun commonParent(paths: List<String>): String? {
        val split = paths.filter { it.startsWith("/") }.map { it.trimEnd('/').split('/').drop(1) }
        if (split.isEmpty()) return null
        var shared = split.first()
        for (parts in split.drop(1)) shared = shared.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        return if (shared.size >= 2 && shared.none { it.isEmpty() || it == ".." }) "/" + shared.joinToString("/") else null
    }

    @Test fun commonParentIsConservative() {
        assertEquals("/home/u/downloads", commonParent(listOf("/home/u/downloads/A", "/home/u/downloads/B/C")))
        assertNull(commonParent(listOf("/home/a", "/srv/b")))
        assertNull(commonParent(listOf("/data")))
        assertNull(commonParent(emptyList()))
    }

    /** One unique synthetic transfer per run, so repeats never collide or touch existing data. */
    private fun syntheticTorrent(name: String): ByteArray {
        val payload = "Chunks acceptance payload\n".toByteArray()
        val piece = MessageDigest.getInstance("SHA-1").digest(payload)
        val head = "d4:infod6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
        return head.toByteArray(Charsets.ISO_8859_1) + piece + "ee".toByteArray()
    }

    @Test fun syntheticFixtureIsValidUniqueV1Metainfo() {
        val first = TorrentMetainfoReader.preview(syntheticTorrent("chunks-acceptance-1.txt"))
        val second = TorrentMetainfoReader.preview(syntheticTorrent("chunks-acceptance-2.txt"))
        assertEquals("chunks-acceptance-1.txt", first.name)
        assertEquals(1, first.fileCount)
        assertNotEquals(first.infoHashV1, second.infoHashV1)
    }

    private suspend fun waitFor(rtorrent: RTorrentRemote, hash: String, condition: (RemoteTransfer?) -> Boolean): RemoteTransfer? {
        var observed: RemoteTransfer? = null
        repeat(25) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (condition(observed)) return observed
            Thread.sleep(400)
        }
        return observed
    }

    @Test fun ownerAuthorizedExactTargetMutationRoundTrip() = runBlocking {
        assumeTrue("Mutation requires apply", System.getenv("CHUNKS_LIVE_MUTATION") == "apply")
        val rtorrent = remote()
        // This host leaves directory.default empty; fall back to the folder every
        // existing transfer lives under, so the test folder sits beside real data.
        val configured = rtorrent.defaultDirectory()
        val base = configured ?: commonParent(rtorrent.listTransfers("main").map { it.directory })
        requireNotNull(base) { "No safe base directory could be determined" }
        log("base directory source=${if (configured != null) "directory.default" else "common parent of inventory"} depth=${base.count { it == '/' }}")
        val target = "$base/chunks-acceptance"
        val runId = System.getenv("GITHUB_RUN_ID").orEmpty().filter { it.isDigit() }.ifEmpty { System.currentTimeMillis().toString() }
        val name = "chunks-acceptance-$runId.txt"
        val bytes = syntheticTorrent(name)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        log("mutation target hash=$hash name=$name directory=<default>/chunks-acceptance")
        assertTrue("Preflight: unique synthetic hash must be absent",
            rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })
        val before = rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet()

        var added: RemoteTransfer? = null
        try {
            val load = rtorrent.loadStoppedTorrentBytes(bytes, target)
            log("load.raw_verbose outcome=${load::class.simpleName}")
            assertTrue("Add must be verified or pending, never uncertain: $load",
                load is RemoteAddOutcome.VerifiedStopped || load is RemoteAddOutcome.PendingVerification)
            added = requireNotNull(waitFor(rtorrent, hash) { it != null }) { "Added hash never appeared" }
            assertFalse("New transfer must stay stopped", added.started)
            assertEquals(target, added.directory)
            assertEquals(name, added.name)
            assertEquals(before + hash.lowercase(), rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet())

            assertEquals(RemoteAddOutcome.AlreadyPresent(hash), rtorrent.loadStoppedTorrentBytes(bytes, target))
            log("duplicate Add refused without a second load")

            val file = rtorrent.listFiles(hash).single()
            val off = rtorrent.setFilePriority(hash, 0, file.path, file.sizeBytes, file.priority, 0)
            log("file priority off outcome=${off::class.simpleName}")
            assertTrue(off is FilePriorityOutcome.Verified || off is FilePriorityOutcome.PendingVerification)
            val back = rtorrent.setFilePriority(hash, 0, file.path, file.sizeBytes, 0, file.priority)
            log("file priority restore outcome=${back::class.simpleName}")
            assertTrue(back is FilePriorityOutcome.Verified || back is FilePriorityOutcome.PendingVerification)
            assertEquals(file.priority, rtorrent.listFiles(hash).single().priority)

            val start = rtorrent.setStarted(hash, true)
            log("start outcome=${start::class.simpleName}")
            assertTrue(start !is LifecycleOutcome.Uncertain)
            assertTrue("Did not start", waitFor(rtorrent, hash) { it?.started == true }?.started == true)
            val stop = rtorrent.setStarted(hash, false)
            log("stop outcome=${stop::class.simpleName}")
            assertTrue(stop !is LifecycleOutcome.Uncertain)
            assertTrue("Did not stop", waitFor(rtorrent, hash) { it?.started == false }?.started == false)
        } finally {
            // Single exact-target cleanup of our own synthetic transfer; never a retry loop.
            val current = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (current != null) {
                val erase = rtorrent.eraseFromSession(hash, current.name, current.directory)
                log("session erase outcome=${erase::class.simpleName}")
                assertTrue(erase is SessionEraseOutcome.VerifiedRemoved || erase is SessionEraseOutcome.PendingRemoval)
                assertNull("Erased transfer still listed", waitFor(rtorrent, hash) { it == null })
            }
        }
        assertEquals("Only our synthetic transfer may have changed", before,
            rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet())
        log("mutation round trip complete; inventory restored to its prior identity set")
    }
}
