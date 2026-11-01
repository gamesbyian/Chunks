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

    @Test fun privateAndLoopbackPeerHintsCannotProbeTheRunnerNetwork() {
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
            "added-stopped", "started", "bytes-verified", "stopped", "removed",
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

        // Inspect the same verified server's actual peer port/address before
        // creating *anything* on it. This TCP preflight sends no BitTorrent
        // handshake, content, login or RPC mutation.
        val source = remote.peerSource(beforeInventory.first().hash)
        val candidates = listOfNotNull(source.announcedAddress, source.boundAddress, source.rpcHost)
            .distinct()
        val peer = candidates.firstNotNullOfOrNull { candidate ->
            val address = runCatching { InetAddress.getByName(candidate) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            if (!publicPeerAddress(address)) return@firstNotNullOfOrNull null
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
            val added = remote.loadStoppedTorrentBytes(metainfo, place)
            // A lost Add ACK is never grounds for a second load call.
            val settled = if (added is RemoteAddOutcome.VerifiedStopped) added
                else remote.reconcileStoppedAdd(plan)
            assertTrue("Exact stopped Add not positively verified",
                settled is RemoteAddOutcome.VerifiedStopped)
            val row = remote.listTransfers("main").singleOrNull {
                it.hash.equals(hash, true)
            }
            assertNotNull("Synthetic Add did not enter the complete main view", row)
            assertEquals(name, row!!.name)
            assertEquals(place, row.directory)
            assertFalse(row.started)
            remoteAdded = true
            stage("added-stopped")

            val initial = remote.readTrafficSample(hash)
            assertEquals(0L, initial.receivedBytes)
            assertEquals(0L, initial.verifiedBytes)
            assertEquals(payload.size.toLong(), initial.expectedBytes)
            val start = remote.setStarted(hash, true)
            assertTrue("Exact Start was not verified or pending",
                start is LifecycleOutcome.Verified || start is LifecycleOutcome.Pending)
            await("host synthetic Start", 30) {
                runBlocking { remote.listTransfers("main").any {
                    it.hash.equals(hash, true) && it.started
                } }
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
            seed.close()
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
                        val removed = runCatching {
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
                                exactSessionRemovedAndInventoryRestored = true
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
        }
    }
}
