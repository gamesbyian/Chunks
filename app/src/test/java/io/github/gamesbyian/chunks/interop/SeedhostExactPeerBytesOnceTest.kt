package io.github.gamesbyian.chunks.interop

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteSmartConnect
import io.github.gamesbyian.chunks.rpc.SeedhostDestinationPreflight
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import io.github.gamesbyian.chunks.rpc.requireRemoteConnectIdentityEvidence
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Gated, one-shot, *real* owner-authorized development-host receipt.
 *
 * The runner seeds one 512 KiB synthetic V1 file; the designated hosted
 * rTorrent downloads real peer pieces from that runner. Both exact-hash
 * received and hash-verified counters must grow from a zero baseline.
 * Nothing happens without explicit trusted-main authorization and a unique
 * non-replayed GitHub run identity. All other hosted jobs remain untouched.
 *
 * This is host/JVM wire acceptance, not installed Android UI or OEM evidence.
 */
class SeedhostExactPeerBytesOnceTest {
    @get:Rule val temp = TemporaryFolder()

    /** No alternative HTTPS port, origin, account mount or embedded login. */
    private fun approvedPrimaryEndpoint(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" &&
            uri.host == "ianseeds.wood.seedhost.eu" &&
            uri.port == -1 &&
            uri.path == "/rutorrent/" &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)

    @Test fun onlyApprovedExactPrimaryEndpointPassesBeforeAnyNetwork() {
        val valid = "https://ianseeds.wood.seedhost.eu/rutorrent/"
        assertTrue(approvedPrimaryEndpoint(valid))
        listOf(
            "https://ianseeds.wood.seedhost.eu:443/rutorrent/",
            "https://ianseeds.wood.seedhost.eu:8443/rutorrent/",
            "https://ianseeds.wood.seedhost.eu/rutorrent2/",
            "https://ianseeds.wood.seedhost.eu/rutorrent",
            "https://ianseeds.wood.seedhost.eu/rutorrent/?token=sample",
            "https://ianseeds.wood.seedhost.eu/rutorrent/#fragment",
            "http://ianseeds.wood.seedhost.eu/rutorrent/",
            "https://someone:secret@ianseeds.wood.seedhost.eu/rutorrent/",
            "https://ianseeds.wood.seedhost.eu.attacker.invalid/rutorrent/"
        ).forEach { assertFalse("Disallowed alternate origin or instance", approvedPrimaryEndpoint(it)) }
    }

    /** The approved development seedbox is Internet-facing, never runner LAN. */
    private fun publicPeerAddress(address: InetAddress): Boolean {
        val bytes = address.address
        val b = bytes.map { it.toInt() and 0xff }
        // Java's isSiteLocalAddress omits carrier-grade NAT, benchmarking,
        // documentation and reserved IPv4 blocks. Never connect a protected
        // acceptance runner to those merely because a server returned them.
        val reservedV4 = b.size == 4 && (
            b[0] == 0 || b[0] == 10 || b[0] == 127 ||
            (b[0] == 100 && b[1] in 64..127) ||
            (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && (b[1] == 168 ||
                (b[1] == 0 && b[2] in listOf(0, 2)) ||
                (b[1] == 88 && b[2] == 99))) ||
            (b[0] == 198 && (b[1] in 18..19 ||
                (b[1] == 51 && b[2] == 100))) ||
            (b[0] == 203 && b[1] == 0 && b[2] == 113) ||
            b[0] >= 224
        )
        val reservedV6 = b.size == 16 && (
            // Admit global-unicast 2000::/3 only, excluding RFC 3849 docs.
            (b[0] and 0xe0) != 0x20 ||
            (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
        )
        return !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isMulticastAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !reservedV4 && !reservedV6
    }

    /** Last independently observed bound rTorrent peer address for this exact
     * development instance (see RTORRENT-COMPATIBILITY-MATRIX.md). This
     * one-shot workflow must be re-reviewed if the provider reassigns it. */
    private fun approvedPeerDestination(address: InetAddress): Boolean =
        publicPeerAddress(address) && address.hostAddress == "5.79.112.219"

    // Reject candidate strings BEFORE resolving them. Server-supplied DNS
    // names do not authorize arbitrary DNS queries, rebinding or outbound
    // probes from a trusted Actions runner. The single approved literal is
    // independently verified against the rTorrent bind address in the matrix.
    private fun approvedPeerLiteral(candidate: String): InetAddress? {
        if (candidate != "5.79.112.219") return null
        return InetAddress.getByName("5.79.112.219").takeIf(::approvedPeerDestination)
    }

    @Test fun privateAndLoopbackPeerHintsCannotProbeTheRunnerNetwork() {
        assertTrue(approvedPeerDestination(InetAddress.getByName("5.79.112.219")))
        assertEquals("5.79.112.219", approvedPeerLiteral("5.79.112.219")?.hostAddress)
        listOf("8.8.8.8", "1.1.1.1", "5.79.112.210", "100.64.0.5",
            "2001:4860:4860::8888").forEach {
            assertFalse("A public IP alone does not authorize another peer",
                approvedPeerDestination(InetAddress.getByName(it)))
        }
        listOf("localhost", "127.0.0.1", "169.254.1.1", "attacker.invalid",
            "seedhost.example", "5.79.112.219.", "005.079.112.219",
            "5.79.112.219\n", "5.79.112.219.evil.example").forEach {
            assertNull("Do not resolve a server-supplied unapproved hostname",
                approvedPeerLiteral(it))
        }
        listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.5.4",
            "192.168.8.5", "169.254.3.4", "100.64.2.1", "198.19.1.1",
            "192.0.2.1", "198.51.100.7", "203.0.113.9", "192.0.0.8",
            "240.0.0.1", "255.255.255.255", "224.0.0.1", "::1",
            "fe80::1", "fc00::1", "fd42::42", "2001:db8::1").forEach { address ->
            assertFalse("Unapproved private/non-routable peer address",
                publicPeerAddress(InetAddress.getByName(address)))
        }
        listOf("8.8.8.8", "2001:4860:4860::8888").forEach { address ->
            assertTrue("Ordinary public peer address rejected",
                publicPeerAddress(InetAddress.getByName(address)))
        }
    }

    private fun stage(value: String) {
        require(value in setOf("preflight", "destination", "peer", "seed-ready",
            "added-stopped", "added-started", "started", "bytes-verified", "stopped", "removed",
            "inventory-restored"))
        println("CHUNKS_HOST_BYTES_STAGE: $value")
    }

    private fun await(what: String, seconds: Int, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) fail("Synthetic exact-host proof timed out: $what")
            Thread.sleep(500)
        }
    }

    /** Independent out-of-process PHP filesystem view, never torrent membership. */
    private fun diskEntries(place: String): Set<String> {
        val output = java.nio.file.Files.createTempFile("chunks-private-file-witness-", ".txt")
        val script = java.nio.file.Paths.get(requireNotNull(System.getenv("GITHUB_WORKSPACE")),
            "tools", "seedhost_file_witness.py").toFile()
        check(script.isFile) { "Independent filesystem witness script missing" }
        val process = ProcessBuilder("python3", script.absolutePath)
            .redirectOutput(output.toFile()).start()
        try {
            process.outputStream.bufferedWriter().use { it.write(place + "\n") }
            check(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) { "Filesystem observer deadline exceeded" }
            if (process.exitValue() != 0) {
                val reason = process.errorStream.bufferedReader().use { it.readLine().orEmpty() }
                if (Regex("FILESYSTEM_WITNESS [A-Za-z]+(?: code=[0-9]{3})?").matches(reason))
                    println("CHUNKS_HOST_DELETE: " + reason)
                error("Independent exact-parent filesystem observer unavailable")
            }
            check(java.nio.file.Files.size(output) <= 2_000_000)
            return java.nio.file.Files.readAllLines(output).filter { it.isNotBlank() }
                .map { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }.toSet()
                .also { check(place.trimEnd('/') + "/" in it) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            java.nio.file.Files.deleteIfExists(output)
        }
    }

    private suspend fun qualifyDeletion(remote: RTorrentRemote, hash: String, name: String,
        place: String, metainfo: ByteArray) {
        require(System.getenv("GITHUB_REF") == "refs/heads/main")
        val cap = remote.discoverDeleteDataCapability(force = true)
        check(cap.actionable) { "No verified deletion adapter" }
        println("CHUNKS_HOST_DELETE: capability=" + cap.adapter!!.name)
        val before = diskEntries(place)
        val payloadPath = place.trimEnd('/') + "/" + name
        check(payloadPath in before) { "Exact completed payload not independently observable" }
        println("CHUNKS_HOST_DELETE: actual-payload-present")
        check(remote.removeWithDataViaRuTorrent(hash, "incorrect-reviewed-name", place) ==
            io.github.gamesbyian.chunks.rpc.RemoteDeleteDataOutcome.Refused(io.github.gamesbyian.chunks.rpc.RemoteDeleteRefusal.TARGET_CHANGED))
        check(remote.lookupName(hash) == name && diskEntries(place) == before)
        println("CHUNKS_HOST_DELETE: stale-review-refused-files-preserved")
        check(remote.eraseFromSession(hash, name, place) is SessionEraseOutcome.VerifiedRemoved)
        check(remote.lookupName(hash) == null && diskEntries(place) == before)
        println("CHUNKS_HOST_DELETE: session-only-removed-payload-preserved")
        val added = remote.loadStoppedTorrentBytes(metainfo, place)
        check(added is RemoteAddOutcome.VerifiedStopped) { "Stopped re-registration not verified" }
        check(remote.lookupName(hash) == name && diskEntries(place) == before)
        // Exactly one destructive submission. A failure from this point is
        // reconciled read-only; it must never fall into session-only cleanup.
        val outcome = remote.removeWithDataViaRuTorrent(hash, name, place)
        println("CHUNKS_HOST_DELETE: outcome=" + outcome.javaClass.simpleName)
        check(outcome is io.github.gamesbyian.chunks.rpc.RemoteDeleteDataOutcome.QueuedForCleanup)
        check(remote.lookupName(hash) == null) { "Exact session not removed" }
        val deadline = System.nanoTime() + 180_000_000_000L
        var after = diskEntries(place)
        while (payloadPath in after && System.nanoTime() < deadline) {
            kotlinx.coroutines.delay(3_000)
            after = diskEntries(place)
        }
        check(payloadPath !in after) { "Collector did not delete actual payload within 180 seconds" }
        check(after == before - payloadPath) { "Unrelated filesystem entries changed" }
        println("CHUNKS_HOST_DELETE: verified-session-absent-payload-absent-unrelated-entries-preserved")
    }

    @Test fun approvedHostedRTorrentReceivesVerifiedPeerBytesFromRunner() = runBlocking {
        assumeTrue("Protected manual host acceptance only",
            System.getenv("CHUNKS_HOST_PEER_PROOF") == "authorized")
        val run = System.getenv("GITHUB_RUN_ID").orEmpty()
        val attempt = System.getenv("GITHUB_RUN_ATTEMPT").orEmpty()
        require(Regex("[0-9]{5,20}").matches(run) && attempt == "1") {
            "A unique non-replayed trusted-main GitHub run is required"
        }
        val urlText = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        require(approvedPrimaryEndpoint(urlText)) {
            "Only the exact owner-approved development RPC URL is allowed"
        }
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotEmpty() && pass.isNotEmpty()) { "Protected login unavailable" }
        DesktopLibtorrent.ensureLoaded()
        val verified = RemoteSmartConnect.connect(urlText, user, pass) { credentials ->
            val connection = RTorrentRemote(credentials)
            connection.version()
            connection.listTransfers("main")
            requireRemoteConnectIdentityEvidence(credentials, connection)
            connection
        }
        require(verified.credentials.endpoint.endsWith("/plugins/httprpc/action.php")) {
            "Unexpected RPC instance"
        }
        val remote = verified.response
        if (System.getenv("CHUNKS_HOST_DELETE_PROOF") == "authorized") {
            // One-off retirement of the owned fixture left by the failed
            // observer run. No Add or arbitrary pre-existing transfer removal.
            val fixtureRun = "43545246997"
            val fixtureName = "chunks-verified-peer-$fixtureRun.bin"
            val sourceFile = File(temp.newFolder("retirement-identity"), fixtureName)
            sourceFile.writeBytes(ByteArray(512 * 1024).also { Random(fixtureRun.toLong()).nextBytes(it) })
            val metainfo = TorrentBuilder().path(sourceFile).flags(TorrentBuilder.V1_ONLY)
                .pieceSize(64 * 1024).addTracker("http://127.0.0.1:1/announce").generate().entry().bencode()
            val expectedHash = TorrentMetainfoReader.preview(metainfo).infoHashV1
            val inventory = remote.listTransfers("main")
            val target = inventory.singleOrNull { it.hash.equals(expectedHash, true) }
            println("CHUNKS_HOST_DELETE: readonly-retirement-reconciliation")
            println("CHUNKS_HOST_DELETE: session-present=" + (target != null))
            println("CHUNKS_HOST_DELETE: exact-lookup-present=" + (remote.lookupName(expectedHash) != null))
            if (target != null) {
                check(target.name == fixtureName && target.sizeBytes == 512L * 1024)
                val page = diskEntries(target.directory)
                println("CHUNKS_HOST_DELETE: payload-present=" + (target.directory.trimEnd('/') + "/" + fixtureName in page))
                val marker = remote.call("d.custom5", listOf(expectedHash))
                println("CHUNKS_HOST_DELETE: deletion-flag-empty=" + (marker == "" || marker == "0" || marker == 0L))
                val files = remote.listFiles(expectedHash)
                val mapped = remote.call("f.multicall", listOf(expectedHash, "", "f.path=", "f.frozen_path=")) as List<*>
                val mapRows = mapped.map { it as List<*> }
                println("CHUNKS_HOST_DELETE: file-count=" + files.size)
                println("CHUNKS_HOST_DELETE: frozen-empty=" + mapRows.all { it[1] == "" })
                println("CHUNKS_HOST_DELETE: frozen-exact=" + mapRows.all { it[1] == target.directory.trimEnd('/') + "/" + it[0] })
                println("CHUNKS_HOST_DELETE: frozen-relative=" + mapRows.any { !(it[1] as String).startsWith("/") && it[1] != "" })
                val normalized = mapRows.all { row ->
                    val frozen = row[1] as String
                    java.nio.file.Paths.get(frozen).normalize().toString() == target.directory.trimEnd('/') + "/" + row[0]
                }
                println("CHUNKS_HOST_DELETE: frozen-lexically-normalizes=" + normalized)
                val defaults = remote.listTransfers("default")
                val selectedPaths = files.map { target.directory.trimEnd('/') + "/" + it.path }.toSet()
                var shared = 0
                for (other in defaults.filterNot { it.hash.equals(expectedHash, true) }) {
                    val otherFiles = remote.listFiles(other.hash)
                    if (otherFiles.any { other.directory.trimEnd('/') + "/" + it.path in selectedPaths }) shared++
                }
                println("CHUNKS_HOST_DELETE: shared-job-count=" + shared)
                println("CHUNKS_HOST_DELETE: complete-default-view=" + (remote.call("view.size_not_visible", listOf("", "default")) == 0L))
                val cap = remote.discoverDeleteDataCapability(force = true)
                println("CHUNKS_HOST_DELETE: capability=" + cap.state.name + " adapter=" + cap.adapter?.name)
            }
            return@runBlocking
        }
        val beforeInventory = remote.listTransfers("main")
        require(beforeInventory.isNotEmpty()) { "No independently corroborated destination" }
        stage("preflight")

        // Reuse the already accepted, read-only multi-observation destination
        // preflight. Do not turn a guessed directory into write authority.
        // Eager List.map is inline: the read-only RPCs must run inside this
        // suspending test, rather than a deferred non-suspending Sequence map.
        val observations = beforeInventory
            .filter { it.sizeBytes > 0 && it.completedBytes == it.sizeBytes }
            .take(48).map { transfer ->
                val multifile = runCatching {
                    (remote.call("d.is_multi_file", listOf(transfer.hash)) as? Number)?.toInt()
                }.getOrNull()
                val fresh = runCatching {
                    remote.call("d.directory", listOf(transfer.hash)) as? String
                }.getOrNull()
                SeedhostDestinationPreflight.Observation(transfer.directory, true,
                    multifile?.let { it == 0 }, fresh == transfer.directory)
            }.toList()
        val place = requireNotNull(SeedhostDestinationPreflight.select(
            remote.defaultDirectory(), observations
        )) { "No corroborated server parent: no Add allowed" }.directory
        stage("destination")
        if (System.getenv("CHUNKS_HOST_DELETE_PROOF") == "authorized") {
            diskEntries(place)
            println("CHUNKS_HOST_DELETE: independent-parent-observed-before-add")
        }

        // Inspect the same verified server's actual peer port/address before
        // creating *anything* on it. This TCP preflight sends no BitTorrent
        // handshake, content, login or RPC mutation.
        val source = remote.peerSource(beforeInventory.first().hash)
        val candidates = listOfNotNull(source.announcedAddress, source.boundAddress, source.rpcHost)
            .distinct()
        val peer = candidates.firstNotNullOfOrNull { candidate ->
            val address = approvedPeerLiteral(candidate) ?: return@firstNotNullOfOrNull null
            val target = InetSocketAddress(address, source.listenPort.toInt())
            if (runCatching {
                    Socket().use { it.connect(target, 4_000) }
                }.isSuccess) target else null
        } ?: error("The selected exact development host's reported peer listener was unreachable; no Add sent")
        stage("peer")

        val name = "chunks-verified-peer-$run.bin"
        val seedParent = temp.newFolder("runner-seed")
        val seedFile = File(seedParent, name)
        val payload = ByteArray(512 * 1024).also { Random(run.toLong()).nextBytes(it) }
        seedFile.writeBytes(payload)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload).toList()
        val metainfo = TorrentBuilder().path(seedFile).flags(TorrentBuilder.V1_ONLY)
            .pieceSize(64 * 1024).addTracker("http://127.0.0.1:1/announce")
            .generate().entry().bencode()
        val hash = TorrentMetainfoReader.preview(metainfo).infoHashV1
        println("CHUNKS_HOST_BYTES_HASH: $hash") // Synthetic-only, never a tracker/passkey.
        require(remote.listTransfers("main").none { it.hash.equals(hash, true) } &&
            remote.lookupName(hash) == null) { "Fresh synthetic hash already exists; no replay" }
        val plan = StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:$hash", place)
        // On the separately authorized trusted-main native acceptance gate,
        // prove the production Add-with-Start path downloads real peer bytes.
        // The legacy manual stopped-first fixture stays available unchanged.
        val nativeStartedAdd =
            System.getenv("CHUNKS_HOST_PEER_PROOF_NATIVE_START") == "authorized"
        val seed = LocalTorrentEngine(LocalEngineConfig(allowMultipleConnectionsPerIp = true))
        var remoteAdded = false
        // The command can apply before its HTTP ACK is lost; this flag only
        // indicates that Add might have been submitted, never that it worked.
        var addMayHaveApplied = false
        var exactSessionRemovedAndInventoryRestored = false
        try {
            seed.start()
            seed.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 256 * 1024))
            val localAdded = seed.addStopped(metainfo, seedParent, listOf(peer))
            val localHash = (localAdded as LocalAddResult.AddedStopped).status.infoHash
            assertEquals(hash.lowercase(), localHash.lowercase())
            stage("seed-ready")

            addMayHaveApplied = true
            if (nativeStartedAdd) {
                // A single reviewed load.raw_start, not a stopped load plus
                // speculative d.start/d.resume. A lost ACK never triggers an
                // automatic second load; read exact state instead.
                val native = remote.loadStartedTorrentBytes(metainfo, place)
                assertTrue("Native Add returned an existing or invalid target: $native",
                    native is RemoteAddAndStart.Started ||
                        native is RemoteAddAndStart.StartNotVerified)
            } else {
                val added = remote.loadStoppedTorrentBytes(metainfo, place)
                val settled = if (added is RemoteAddOutcome.VerifiedStopped) added
                    else remote.reconcileStoppedAdd(plan)
                assertTrue("Exact stopped Add not positively verified",
                    settled is RemoteAddOutcome.VerifiedStopped)
            }
            val row = await("exact newly loaded native/legacy session", 30) {
                runBlocking {
                    val item = remote.listTransfers("main").singleOrNull {
                        it.hash.equals(hash, true)
                    }
                    item != null && item.name == name && item.directory == place
                }
            }.let {
                remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
            }
            assertNotNull("Synthetic Add did not enter the complete main view", row)
            assertEquals(name, row!!.name)
            assertEquals(place, row.directory)
            // Only our newly reviewed exact hash+name+Place can be retired.
            remoteAdded = true
            if (nativeStartedAdd) {
                stage("added-started")
            } else {
                assertFalse(row.started)
                stage("added-stopped")
            }

            val initial = remote.readTrafficSample(hash)
            assertEquals(0L, initial.receivedBytes)
            assertEquals(0L, initial.verifiedBytes)
            assertEquals(payload.size.toLong(), initial.expectedBytes)
            if (!nativeStartedAdd) {
                val start = remote.setStarted(hash, true)
                assertTrue("Exact Start was not verified or pending",
                    start is LifecycleOutcome.Verified || start is LifecycleOutcome.Pending)
            }
            await("host synthetic active Start", 30) {
                runBlocking {
                    val transfer = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
                    transfer?.started == true && remote.readActivity(hash).active == true
                }
            }
            stage("started")

            assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(hash).outcome)
            await("local runner seeder recheck", 35) {
                seed.status(hash)?.state == LocalTransferState.SEEDING
            }
            await("server received and verified every synthetic piece", 150) {
                runBlocking {
                    val sample = remote.readTrafficSample(hash)
                    sample.hash.equals(hash, true) &&
                        (sample.receivedBytes ?: 0) >= payload.size &&
                        sample.verifiedBytes == payload.size.toLong()
                }
            }
            val final = remote.readTrafficSample(hash)
            assertEquals(hash.lowercase(), final.hash.lowercase())
            assertTrue(final.monotonicNanos > initial.monotonicNanos)
            assertEquals(payload.size.toLong(), final.expectedBytes)
            assertEquals(payload.size.toLong(), final.verifiedBytes)
            assertTrue(final.receivedBytes != null &&
                final.receivedBytes!! > initial.receivedBytes!!)
            await("independent runner per-hash upload counter", 30) {
                (seed.status(hash)?.payloadUploadedBytes ?: 0L) >= payload.size
            }
            assertEquals(expectedSha, MessageDigest.getInstance("SHA-256")
                .digest(seedFile.readBytes()).toList())
            stage("bytes-verified")
        } finally {
            // A JNI/runner teardown exception must not prevent the exact
            // owned session Stop/Erase reconciliation from being attempted.
            // Never report a successful test if the local seeder failed close.
            val seedCloseFailure = runCatching { seed.close() }.exceptionOrNull()
            // This is a single explicitly authorized synthetic lifecycle.
            // Read-only exact-hash/name/directory guards precede each cleanup.
            // A lost Stop/Erase acknowledgement never triggers a second write.
            if (remoteAdded) {
                val row = runCatching { remote.listTransfers("main").singleOrNull {
                    it.hash.equals(hash, true)
                } }.getOrNull()
                if (row != null && row.name == name && row.directory == place) {
                    runCatching { remote.setStarted(hash, false) }
                    val stopped = runCatching { remote.listTransfers("main").singleOrNull {
                        it.hash.equals(hash, true)
                    } }.getOrNull()
                    if (stopped != null && !stopped.started &&
                        stopped.name == name && stopped.directory == place &&
                        runCatching { remote.readTrafficSample(hash).active == false }.getOrDefault(false)) {
                        stage("stopped")
                        val removed = if (System.getenv("CHUNKS_HOST_DELETE_PROOF") == "authorized") {
                            qualifyDeletion(remote, hash, name, place, metainfo)
                            SessionEraseOutcome.VerifiedRemoved(hash)
                        } else runCatching {
                            remote.eraseFromSession(hash, name, place)
                        }.getOrNull()
                        // A PendingRemoval still has an exact-session hash.
                        // Missing from the main view cannot override that
                        // readback or produce a false successful-cleanup receipt.
                        if (removed is SessionEraseOutcome.VerifiedRemoved) {
                            val remaining = runCatching { remote.listTransfers("main") }.getOrNull()
                            if (remaining != null &&
                                remaining.map { it.hash.lowercase() }.toSet() ==
                                beforeInventory.map { it.hash.lowercase() }.toSet()) {
                                stage("removed")
                                stage("inventory-restored")
                                exactSessionRemovedAndInventoryRestored = seedCloseFailure == null
                            }
                        }
                    }
                }
            }
            // Report uncertain post-submission outcomes even when Add was
            // never positively read back. The printed synthetic hash allows
            // manual read-only reconciliation without risking a duplicate Add
            // or another d.erase after a lost acknowledgement.
            if (addMayHaveApplied && !exactSessionRemovedAndInventoryRestored)
                println("CHUNKS_HOST_BYTES_STAGE: manual-reconciliation-required")
            if (seedCloseFailure != null) {
                // No exception details: native diagnostics can contain paths.
                // Remote cleanup above was still attempted exactly once.
                error("Runner seeder did not shut down cleanly; reconcile synthetic hash")
            }
        }
    }
}
