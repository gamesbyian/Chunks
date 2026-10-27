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

    /**
     * Pure read-only window on an existing, incomplete started transfer. This
     * may be someone else's already-running authorized development torrent:
     * never send a lifecycle or tracker command, print its hash/name/path, or
     * claim that the sample itself created traffic. A zero eligible count is
     * useful coverage data but explicitly not a download success.
     */
    @Test fun readOnlyCurrentHostTrafficWindow() = runBlocking {
        val rtorrent = remote()
        val candidates = rtorrent.listTransfers("main").filter {
            it.started && it.sizeBytes > 0 && it.completedBytes in 0L until it.sizeBytes
        }.sortedBy { it.hash.lowercase() }
        log("traffic incompleteStartedCount=${candidates.size}")
        // This is a diagnostic sample of a real but owner-authorized inventory,
        // not a mandate to scan thousands of torrents or change their state.
        val selected = candidates.take(6)
        if (selected.isEmpty()) {
            log("traffic evidence=no-eligible-existing-incomplete-started-transfer")
            return@runBlocking
        }
        val samples = selected.map { mutableListOf<RemoteTrafficSample>() }
        val failed = BooleanArray(selected.size)
        repeat(3) { round ->
            if (round > 0) kotlinx.coroutines.delay(3_000L)
            selected.forEachIndexed { index, transfer ->
                if (!failed[index]) {
                    try {
                        samples[index] += rtorrent.readTrafficSample(transfer.hash)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // A transfer could vanish or an RPC capability could
                        // disappear. Never retry a mutation or attribute bytes
                        // from a different identity; mark this case unknown.
                        failed[index] = true
                    }
                }
            }
        }
        val windows = selected.indices.mapNotNull { i ->
            if (failed[i] || samples[i].size != 3) null
            else runCatching { RemoteTrafficWindow.evaluate(samples[i]) }.getOrNull()
        }
        val usable = windows.count {
            it.verdict != TrafficVerdict.INSUFFICIENT_EVIDENCE &&
                it.verdict != TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED
        }
        val unknownOrReset = selected.size - usable
        log("traffic inspected=${selected.size} stable=${windows.size} " +
            "active=${windows.count { it.observedActive == true }} " +
            "inactive=${windows.count { it.observedActive == false }} " +
            "withPeers=${windows.count { (it.peakPeers ?: 0L) > 0L }} " +
            "withReceivedGrowth=${windows.count { (it.receivedDelta ?: 0L) > 0L }} " +
            "withVerifiedGrowth=${windows.count { (it.verifiedDelta ?: 0L) > 0L }} " +
            "noGrowth=${windows.count { it.verdict == TrafficVerdict.NO_PROGRESS_OBSERVED }} " +
            "unknownOrReset=$unknownOrReset")
        assertTrue("At least one existing exact hash must permit stable read-only samples",
            windows.isNotEmpty())
        assertTrue("Independent three-sample windows must span elapsed time",
            windows.all { it.elapsedMillis >= 5_000L })
    }

    /**
     * Read-only tracker announce-history witness for the same bounded
     * incomplete/started subset as the traffic window. URL, announce message,
     * peer IP and passkey getters are not called. Report aggregate counts only.
     * A failed or unsupported getter means unknown, never zero or success.
     */
    @Test fun readOnlyCurrentHostTrackerHistory() = runBlocking {
        val remote = remote()
        val candidates = remote.listTransfers("main").filter {
            it.started && it.sizeBytes > 0 && it.completedBytes in 0L until it.sizeBytes
        }.sortedBy { it.hash.lowercase() }.take(6)
        var supported = 0
        var trackerless = 0
        var allDisabled = 0
        var successSeen = 0
        var failureOnly = 0
        var announcedPeers = 0
        var peerCountersMissing = 0
        for (transfer in candidates) {
            val history = try {
                remote.readTrackerHealth(transfer.hash)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (history != null) {
                supported++
                if (history.total == 0) trackerless++
                if (history.total > 0 && history.enabled == 0) allDisabled++
                if (history.successfulAnnounces > 0L) successSeen++
                if (history.successfulAnnounces == 0L && history.failedAnnounces > 0L) failureOnly++
                if ((history.maxAnnouncedPeers ?: 0L) > 0L) announcedPeers++
                if (history.maxAnnouncedPeers == null) peerCountersMissing++
            }
        }
        log("trackers candidates=${candidates.size} supported=$supported " +
            "trackerless=$trackerless allDisabled=$allDisabled " +
            "successfulHistory=$successSeen failuresOnly=$failureOnly " +
            "withAnnouncedPeers=$announcedPeers peerCountersMissing=$peerCountersMissing " +
            "unknown=${candidates.size - supported}")
        // This is a capability witness, not a proof of peer availability.
        assertTrue("Bounded read-only diagnostic must use at most six existing torrents",
            candidates.size <= 6)
    }

    /**
     * Read only exact-target file selection and engine-open state for the
     * small incomplete started cohort. No file paths, names or hashes logged;
     * use counts only. Unknown capabilities must not be treated as "skipped".
     */
    @Test fun readOnlyCurrentHostFileSelectionAndOpenState() = runBlocking {
        val rtorrent = remote()
        val candidates = rtorrent.listTransfers("main").filter {
            it.started && it.sizeBytes > 0 && it.completedBytes in 0L until it.sizeBytes
        }.sortedBy { it.hash.lowercase() }.take(6)
        var filesRead = 0
        var selected = 0
        var allSkipped = 0
        var mixed = 0
        var open = 0
        var notOpen = 0
        var priorityUnknown = 0
        var noFiles = 0
        var selectedIncomplete = 0
        for (transfer in candidates) {
            val activity = try { rtorrent.readActivity(transfer.hash) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            when (activity?.open) {
                true -> open++
                false -> notOpen++
                else -> Unit
            }
            val files = try {
                val preHash = rtorrent.call("d.hash", listOf(transfer.hash)) as? String
                check(preHash?.equals(transfer.hash, true) == true)
                val items = rtorrent.listFiles(transfer.hash)
                val postHash = rtorrent.call("d.hash", listOf(transfer.hash)) as? String
                check(postHash?.equals(transfer.hash, true) == true)
                items
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (files == null || files.any { it.priority !in 0..2 }) {
                priorityUnknown++
                continue
            }
            filesRead++
            if (files.isEmpty()) { noFiles++; continue }
            val enabled = files.count { it.priority > 0 && it.sizeBytes > 0L }
            val skipped = files.count { it.priority == 0 && it.sizeBytes > 0L }
            if (enabled == 0) allSkipped++
            else {
                selected++
                if (skipped > 0) mixed++
                if (transfer.completedBytes < transfer.sizeBytes) selectedIncomplete++
            }
        }
        log("selection candidates=${candidates.size} filesRead=$filesRead " +
            "hasSelectedFiles=$selected allSkipped=$allSkipped mixed=$mixed " +
            "noFiles=$noFiles priorityUnknown=$priorityUnknown " +
            "open=$open notOpen=$notOpen " +
            "hasSelectedFilesWhileIncomplete=$selectedIncomplete")
        assertTrue("At most six existing transfers may be inspected", candidates.size <= 6)
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

    /**
     * Read-only host destination preflight. An arbitrary common prefix of all
     * torrent paths was the unverified assumption in the failed first run.
     * Read back each bounded complete single-file candidate's actual directory
     * before nominating a parent for a different uniquely named test file.
     * Neither existing downloaded bytes nor files are mutated by this method.
     */
    private suspend fun preflightExistingParent(rtorrent: RTorrentRemote): SeedhostDestinationPreflight.Selection? {
        log("preflight phase=read-default")
        val configured = rtorrent.defaultDirectory()
        log("preflight configuredDefaultPresent=${configured != null}")
        log("preflight phase=read-inventory")
        val inventory = rtorrent.listTransfers("main")
        val candidates = inventory.filter {
            it.sizeBytes > 0 && it.completedBytes == it.sizeBytes
        }.take(48)
        log("preflight inventory=${inventory.size} completeCandidatesInspected=${candidates.size}")
        val observations = candidates.map { transfer ->
            val multi = try {
                (rtorrent.call("d.is_multi_file", listOf(transfer.hash)) as? Number)?.toInt()
            } catch (_: RpcFault) { null }
            val freshPath = try {
                rtorrent.call("d.directory", listOf(transfer.hash)) as? String
            } catch (_: RpcFault) { null }
            SeedhostDestinationPreflight.Observation(
                directory = transfer.directory,
                complete = true,
                singleFile = multi?.let { it == 0 },
                freshDirectoryMatches = freshPath == transfer.directory
            )
        }
        val chosen = SeedhostDestinationPreflight.select(configured, observations)
        log("preflight result=${if (chosen == null) "indeterminate" else "supported"} " +
            "configuredCorroborated=${chosen?.usedConfiguredDefault == true} " +
            "singleFileReferences=${chosen?.independentReferences ?: 0}")
        return chosen
    }

    @Test fun readOnlyExistingDestinationPreflight() = runBlocking {
        val rtorrent = remote()
        val chosen = preflightExistingParent(rtorrent)
        assertNotNull("No sufficiently corroborated existing single-file destination; no write permitted", chosen)
    }

    /** One unique synthetic transfer per run, so repeats never collide or touch existing data. */
    private fun syntheticTorrent(name: String): ByteArray {
        val payload = "Chunks acceptance payload\n".toByteArray()
        val piece = MessageDigest.getInstance("SHA-1").digest(payload)
        // The development Seedhost silently drops tracker-less torrents; a stopped
        // transfer never contacts this unroutable announce address.
        val announce = "http://127.0.0.1:1/announce"
        val head = "d8:announce${announce.length}:${announce}4:infod6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
        return head.toByteArray(Charsets.ISO_8859_1) + piece + "ee".toByteArray()
    }

    @Test fun syntheticFixtureIsValidUniqueV1Metainfo() {
        val first = TorrentMetainfoReader.preview(syntheticTorrent("chunks-acceptance-1.txt"))
        val second = TorrentMetainfoReader.preview(syntheticTorrent("chunks-acceptance-2.txt"))
        assertEquals("chunks-acceptance-1.txt", first.name)
        assertEquals(1, first.fileCount)
        assertNotEquals(first.infoHashV1, second.infoHashV1)
    }

    private suspend fun waitFor(rtorrent: RTorrentRemote, hash: String, attempts: Int = 25,
                                condition: (RemoteTransfer?) -> Boolean): RemoteTransfer? {
        var observed: RemoteTransfer? = null
        repeat(attempts) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (condition(observed)) return observed
            Thread.sleep(400)
        }
        return observed
    }

    /**
     * Read-only reconciliation of an earlier acknowledged-but-unlisted Add. Only a
     * transfer whose name proves it is one of our synthetic fixtures may be erased.
     */
    @Test fun reconcilePreviouslyUncertainSyntheticAdd() = runBlocking {
        val hash = System.getenv("CHUNKS_LIVE_RECONCILE_HASH").orEmpty().lowercase()
        assumeTrue("Only when a hash to reconcile is supplied", hash.isNotBlank())
        require(Regex("[0-9a-f]{40}").matches(hash))
        val rtorrent = remote()
        val listed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
        val name = rtorrent.lookupName(hash)
        log("reconcile hash=$hash listedInMain=${listed != null} sessionLookup=${name != null}")
        if (listed != null && System.getenv("CHUNKS_LIVE_MUTATION") != "apply") {
            log("reconcile found the transfer; erase needs apply_mutation=true")
        } else if (listed != null) {
            require(listed.name.startsWith("chunks-acceptance-") && listed.name.endsWith(".txt")) {
                "Refusing to erase a transfer that is not a Chunks synthetic fixture"
            }
            val erase = rtorrent.eraseFromSession(listed.hash, listed.name, listed.directory)
            log("reconcile erase outcome=${erase::class.simpleName}")
            assertNull(waitFor(rtorrent, hash) { it == null })
        }
    }

    @Test fun ownerAuthorizedExactTargetMutationRoundTrip() = runBlocking {
        assumeTrue("Mutation requires apply", System.getenv("CHUNKS_LIVE_MUTATION") == "apply")
        assumeTrue("Diagnostic runs do not repeat the round trip", System.getenv("CHUNKS_LIVE_DIAGNOSE_ADD") != "true")
        val rtorrent = remote()
        log("roundtrip phase=preflight")
        val selected = preflightExistingParent(rtorrent)
        requireNotNull(selected) { "Existing download directory could not be corroborated; no write attempted" }
        // This is a previously observed parent of a complete SINGLE-FILE
        // transfer. It is stronger evidence than an inventory-wide path prefix,
        // but it still does not prove writable permissions; Add must read back.
        val target = selected.directory
        log("roundtrip phase=identity-preflight")
        val runId = System.getenv("GITHUB_RUN_ID").orEmpty().filter { it.isDigit() }.ifEmpty { System.currentTimeMillis().toString() }
        val name = "chunks-acceptance-$runId.txt"
        val bytes = syntheticTorrent(name)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        log("mutation target hash=$hash name=$name directory=<base>")
        assertTrue("Preflight: unique synthetic hash must be absent",
            rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })
        val before = rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet()

        var added: RemoteTransfer? = null
        try {
            log("roundtrip phase=add-stopped")
            val load = rtorrent.loadStoppedTorrentBytes(bytes, target)
            log("load.raw_verbose outcome=${load::class.simpleName}")
            assertTrue("Add must be verified or pending, never uncertain: $load",
                load is RemoteAddOutcome.VerifiedStopped || load is RemoteAddOutcome.PendingVerification)
            added = waitFor(rtorrent, hash, attempts = 150) { it != null }
            if (added == null) log("not listed after reconciliation window; d.name lookup=${rtorrent.lookupName(hash) != null}")
            requireNotNull(added) { "Added hash never appeared" }
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

            log("roundtrip phase=start")
            val start = rtorrent.setStarted(hash, true)
            log("start outcome=${start::class.simpleName}")
            assertTrue(start !is LifecycleOutcome.Uncertain)
            assertTrue("Did not start", waitFor(rtorrent, hash) { it?.started == true }?.started == true)
            // d.state=1 alone includes paused transfers; this exact-host probe
            // must also verify the engine actually activated the fixture.
            var active = false
            for (attempt in 1..25) {
                active = rtorrent.readActivity(hash).active == true
                if (active) break
                kotlinx.coroutines.delay(400)
            }
            assertTrue("The development Seedhost never reported active after Start", active)
            log("rTorrent active state verified after complete Open/Start/Resume")
            log("roundtrip phase=stop")
            val stop = rtorrent.setStarted(hash, false)
            log("stop outcome=${stop::class.simpleName}")
            assertTrue(stop !is LifecycleOutcome.Uncertain)
            assertTrue("Did not stop", waitFor(rtorrent, hash) { it?.started == false }?.started == false)
        } finally {
            // Single exact-target cleanup of our own synthetic transfer; never a retry loop.
            log("roundtrip phase=finally-reconcile-exact-hash")
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

    /** A magnet with a tracker, as a user would paste it; synthetic identity, erased afterwards. */
    @Test fun ownerAuthorizedMagnetStoppedAdd() = runBlocking {
        assumeTrue("Mutation requires apply", System.getenv("CHUNKS_LIVE_MUTATION") == "apply")
        assumeTrue("Diagnostic runs do not repeat writes", System.getenv("CHUNKS_LIVE_DIAGNOSE_ADD") != "true")
        val rtorrent = remote()
        // The host may have no directory.default and existing torrents may
        // occupy unrelated parents. Only a fresh complete single-file readback
        // may nominate a safe existing parent; fail closed without writing.
        val selected = preflightExistingParent(rtorrent)
        requireNotNull(selected) { "No verified existing single-file parent; no write permitted" }
        val base = selected.directory
        val run = System.getenv("GITHUB_RUN_ID").orEmpty().filter { it.isDigit() }.ifEmpty { System.currentTimeMillis().toString() }
        val hash = MessageDigest.getInstance("SHA-1").digest("chunks-acceptance-magnet-$run".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val magnet = "magnet:?xt=urn:btih:$hash&dn=chunks-acceptance-magnet-$run&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce"
        val plan = StoppedRemoteAddPlan.fromMagnet(magnet, base)
        val before = rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet()
        assertFalse(hash in before)
        try {
            val outcome = rtorrent.loadStopped(plan)
            log("magnet load.verbose outcome=${outcome::class.simpleName}")
            assertTrue("Magnet Add must never be Uncertain: $outcome", outcome !is RemoteAddOutcome.Uncertain)
            val listed = waitFor(rtorrent, hash, attempts = 60) { it != null }
            log("magnet retained=${listed != null} started=${listed?.started} dirIsBase=${listed?.directory == base} " +
                "nameIsHashOrDn=${listed?.name?.let { it.equals(hash, true) || it.startsWith("chunks-acceptance-magnet") }}")
            requireNotNull(listed) { "Magnet transfer never appeared" }
            assertFalse("Magnet Add must stay stopped", listed.started)
        } finally {
            val current = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (current != null) {
                val erase = rtorrent.eraseFromSession(current.hash, current.name, current.directory)
                log("magnet session erase outcome=${erase::class.simpleName}")
                assertNull(waitFor(rtorrent, hash) { it == null })
            }
        }
        assertEquals(before, rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet())
    }

    /** ruTorrent's own upload form with a tracker-bearing synthetic torrent; erased afterwards. */
    @Test fun ownerAuthorizedRuTorrentUploadStoppedAdd() = runBlocking {
        assumeTrue("Mutation requires apply", System.getenv("CHUNKS_LIVE_MUTATION") == "apply")
        assumeTrue("Diagnostic runs do not repeat writes", System.getenv("CHUNKS_LIVE_DIAGNOSE_ADD") != "true")
        val rtorrent = remote()
        // The host may have no directory.default and existing torrents may
        // occupy unrelated parents. Only a fresh complete single-file readback
        // may nominate a safe existing parent; fail closed without writing.
        val selected = preflightExistingParent(rtorrent)
        requireNotNull(selected) { "No verified existing single-file parent; no write permitted" }
        val base = selected.directory
        val run = System.getenv("GITHUB_RUN_ID").orEmpty().filter { it.isDigit() }.ifEmpty { System.currentTimeMillis().toString() }
        val name = "chunks-acceptance-$run-upload.txt"
        val bytes = syntheticTorrent(name)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val before = rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet()
        assertFalse(hash in before)
        try {
            val outcome = rtorrent.loadStoppedViaRuTorrentUpload(bytes, base)
            log("rutorrent upload outcome=${outcome::class.simpleName}")
            val listed = waitFor(rtorrent, hash) { it != null }
            log("rutorrent upload retained=${listed != null} started=${listed?.started} dirIsBase=${listed?.directory == base} " +
                "nameMatches=${listed?.name == name}")
            assertTrue("Upload Add must verify stopped: $outcome", outcome is RemoteAddOutcome.VerifiedStopped)
        } finally {
            val current = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (current != null && current.name == name) {
                val erase = rtorrent.eraseFromSession(current.hash, current.name, current.directory)
                log("rutorrent upload session erase outcome=${erase::class.simpleName}")
                assertNull(waitFor(rtorrent, hash) { it == null })
            }
        }
        assertEquals(before, rtorrent.listTransfers("main").map { it.hash.lowercase() }.toSet())
    }
}
