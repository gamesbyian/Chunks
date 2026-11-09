package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses the real RTorrentRemote HTTP/XML-RPC adapter against a localhost fixture.
 * Contract/negative evidence only: not installed rTorrent, an authenticated
 * Seedhost session, publisher bytes, actual swarm peers or physical Android.
 */
class RemoteUniversalAddAdapterTest {
    private val hash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash&tr=https%3A%2F%2Ftracker.example%2Fsecret"
    private val place = "/downloads"

    private fun ticket(
        scope: String,
        source: UniversalAddSource = UniversalAddSource.magnet(magnet, hash),
        where: UniversalAddPlace = UniversalAddPlace.ServerCustom(place),
        start: Boolean = true
    ): UniversalAddCoordinator.Ticket {
        val intent = UniversalAddIntent(
            source, UniversalAddTarget.AuthenticatedServer(scope), where,
            startWhenAdded = start, allowArtwork = true)
        val coordinator = UniversalAddCoordinator()
        assertTrue(coordinator.review(
            intent, EnvironmentCapabilities.unprobed(intent.target.environment),
            EnvironmentAvailability.LIVE).accepted)
        assertTrue(coordinator.authorizeByUser())
        return requireNotNull(coordinator.beginSubmission(1000L))
    }

    private fun value(text: String) = "<value><string>$text</string></value>"
    private fun number(n: Long) = "<value><i8>$n</i8></value>"

    private fun respond(exchange: HttpExchange, payload: String, status: Int = 200) {
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val body = "<methodResponse><params><param>$payload</param></params></methodResponse>"
            .toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun absent(exchange: HttpExchange) {
        val xml = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>-501</int></value></member>
            <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
            </struct></value></fault></methodResponse>""".toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, xml.size.toLong())
        exchange.responseBody.use { it.write(xml) }
    }

    private fun row(started: Long, directory: String): String {
        val fields = listOf(
            value(hash), value("Sample"), number(100), number(0),
            number(started), value(directory), number(0), number(0), number(0))
        return "<value><array><data><value><array><data>" +
            fields.joinToString("") + "</data></array></value></data></array></value>"
    }

    private fun withFixture(
        initialPresent: Boolean = false,
        start: Boolean = true,
        lostAck: Boolean = false,
        observedDirectory: String = place,
        unauthorized: Boolean = false,
        hiddenFromMain: Boolean = false,
        observedStarted: Boolean = start,
        multiFileFlag: Long = 0,
        metadataPending: Long = 0,
        transientMissingNameOnce: Boolean = false,
        observedDirectoryAfterFirstRead: String? = null,
        reportedHash: String = hash,
        body: suspend (
            RemoteUniversalAddAdapter, UniversalAddCoordinator.Ticket,
            MutableList<String>, () -> RTorrentRemote
        ) -> Unit
    ) = runBlocking {
        var loaded = initialPresent
        var nameReads = 0
        var directoryReads = 0
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(xml)?.groupValues?.get(1).orEmpty()
            methods += method
            if (unauthorized) {
                respond(exchange, "", 401)
                return@createContext
            }
            if (method == "d.name") nameReads++
            if ((method == "d.name" && (!loaded ||
                    (transientMissingNameOnce && nameReads == 1))) ||
                (method == "d.hash" && !loaded)) {
                absent(exchange)
                return@createContext
            }
            if (method in setOf("load.start", "load.verbose")) {
                loaded = true
                if (lostAck) {
                    respond(exchange, "", 503)
                    return@createContext
                }
            }
            val response = when (method) {
                "d.multicall2" -> if (loaded && !hiddenFromMain) row(if (observedStarted) 1 else 0, observedDirectory)
                    else "<value><array><data></data></array></value>"
                "d.name" -> value("Sample")
                "d.hash" -> value(reportedHash)
                "d.directory" -> {
                    directoryReads++
                    value(if (directoryReads > 1) observedDirectoryAfterFirstRead ?: observedDirectory
                        else observedDirectory)
                }
                "d.is_multi_file" -> number(multiFileFlag)
                "d.is_meta" -> number(metadataPending)
                "d.state", "d.is_active" -> number(if (observedStarted) 1 else 0)
                "load.start", "load.verbose" -> number(0)
                else -> throw AssertionError("Unexpected rTorrent RPC: $method")
            }
            respond(exchange, response)
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
            fun connection() = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            val remote = connection()
            val reviewed = ticket(remote.addAccountScope(), start = start)
            body(RemoteUniversalAddAdapter(remote), reviewed, methods, ::connection)
        } finally {
            server.stop(0)
        }
    }

    @Test fun nativeStartedAddUsesOnlyOneLoadAndExactHashPlaceProof() =
        withFixture { adapter, approved, methods, _ ->
            assertEquals(RemoteAddAndStart.Started(hash), adapter.submit(approved).result)
            assertEquals(1, methods.count { it == "load.start" })
            assertFalse(methods.any { it == "d.start" || it == "load.verbose" })
        }

    @Test fun nativeStartLostAcknowledgementReconcilesThroughNewConnectionWithoutRetry() =
        withFixture(lostAck = true) { adapter, approved, methods, reconnect ->
            assertEquals(RemoteAddAndStart.StartNotVerified(hash, null), adapter.submit(approved).result)
            assertEquals(listOf("d.multicall2", "d.name", "load.start"), methods)
            assertEquals(RemoteAddAndStart.Started(hash),
                RemoteUniversalAddAdapter(reconnect()).reconcile(approved))
            assertEquals(1, methods.count { it == "load.start" })
            assertFalse(methods.any { it == "d.start" || it == "load.verbose" })
        }

    @Test fun stoppedAddLostAcknowledgementReconcilesExactPlaceWithoutStart() =
        withFixture(start = false, lostAck = true) { adapter, approved, methods, reconnect ->
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
                adapter.submit(approved).result)
            assertEquals(
                RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, place)),
                RemoteUniversalAddAdapter(reconnect()).reconcile(approved))
            assertEquals(1, methods.count { it == "load.verbose" })
            assertFalse(methods.any { it == "load.start" || it == "d.start" })
        }

    @Test fun existingHashNeverSendsAddOrStart() =
        withFixture(initialPresent = true) { adapter, approved, methods, _ ->
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)),
                adapter.submit(approved).result)
            assertEquals(listOf("d.multicall2"), methods)
        }

    @Test fun wrongPlaceCannotSettleUncertainStartAfterReconnect() =
        withFixture(lostAck = true, observedDirectory = "/different") { adapter, approved, methods, reconnect ->
            assertTrue(adapter.submit(approved).result is RemoteAddAndStart.StartNotVerified)
            assertTrue(RemoteUniversalAddAdapter(reconnect()).reconcile(approved) is RemoteAddAndStart.StartNotVerified)
            assertEquals(1, methods.count { it == "load.start" })
        }

    @Test fun validMultiFileDirectoryRemainsVerifiableForStartedAdd() =
        withFixture(observedDirectory = "$place/Sample", multiFileFlag = 1) {
                adapter, approved, methods, _ ->
            assertEquals(RemoteAddAndStart.Started(hash), adapter.submit(approved).result)
            assertEquals(1, methods.count { it == "load.start" })
            assertFalse(methods.any { it == "d.start" })
        }

    @Test fun malformedMultiFileFlagCannotConfirmStartedAddOrRegisteredRecovery() =
        withFixture(observedDirectory = "$place/Sample", multiFileFlag = 2,
            lostAck = true) { adapter, approved, methods, reconnect ->
            assertTrue(adapter.submit(approved).result is RemoteAddAndStart.StartNotVerified)
            val refreshed = RemoteUniversalAddAdapter(reconnect())
            assertTrue(refreshed.reconcile(approved) is RemoteAddAndStart.StartNotVerified)
            try {
                refreshed.registeredJobWithUnverifiedStart(approved)
                fail("Malformed multi-file flag must not prove reviewed Place")
            } catch (_: IllegalArgumentException) {
                // Read-only recovery retains the attempted-write fence.
            }
            assertEquals(1, methods.count { it == "load.start" })
            assertTrue(methods.none { it == "d.start" || it == "load.verbose" })
        }

    @Test fun malformedMultiFileFlagCannotVerifyStoppedAddAfterOneLoad() =
        withFixture(start = false, observedDirectory = "$place/Sample",
            multiFileFlag = -1) { adapter, approved, methods, reconnect ->
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
                adapter.submit(approved).result)
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
                RemoteUniversalAddAdapter(reconnect()).reconcile(approved))
            assertEquals(1, methods.count { it == "load.verbose" })
            assertTrue(methods.none { it == "d.start" || it == "load.start" })
        }

    @Test fun validMultiFileHiddenJobVerifiesOnlyTheExactSessionRoot() =
        withFixture(start = false, hiddenFromMain = true,
            observedDirectory = "$place/Sample", multiFileFlag = 1) {
                adapter, approved, methods, reconnect ->
            val expected = RemoteAddAndStart.AddedStopped(
                RemoteAddOutcome.VerifiedStopped(hash, "$place/Sample"))
            assertEquals(expected, adapter.submit(approved).result)
            assertEquals(expected, RemoteUniversalAddAdapter(reconnect()).reconcile(approved))
            assertEquals(1, methods.count { it == "load.verbose" })
            assertTrue(methods.none { it == "load.start" || it == "d.start" })
        }

    @Test fun malformedMultiFileHiddenJobCannotVerifyStoppedPlaceAfterLostAck() =
        withFixture(start = false, hiddenFromMain = true, lostAck = true,
            observedDirectory = "$place/Sample", multiFileFlag = 2) {
                adapter, approved, methods, reconnect ->
            val uncertain = RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash))
            assertEquals(uncertain, adapter.submit(approved).result)
            assertEquals(uncertain, RemoteUniversalAddAdapter(reconnect()).reconcile(approved))
            assertEquals(1, methods.count { it == "load.verbose" })
            assertTrue(methods.none { it == "load.start" || it == "d.start" })
        }

    @Test fun authenticationLossBeforeLoadNeverMutates() =
        withFixture(unauthorized = true) { adapter, approved, methods, _ ->
            try {
                adapter.submit(approved)
                fail("Authentication failure must not be a verified Add")
            } catch (_: RemotePreWriteRefusal) {
                assertEquals(listOf("d.multicall2"), methods)
                assertFalse(methods.any { it.startsWith("load.") || it == "d.start" })
            }
        }

    @Test fun differentAccountAndNumberedRouteFailBeforeAnyRpc() =
        withFixture { adapter, approved, methods, _ ->
            val wrongScopes = listOf(
                "https://host.example/rutorrent3/plugins/httprpc/action.php\u0000owner",
                (approved.intent.target as UniversalAddTarget.AuthenticatedServer).accountScope + "other")
            for (scope in wrongScopes) {
                val wrong = ticket(scope)
                try {
                    adapter.submit(wrong)
                    fail("A different exact target must never be used")
                } catch (_: RemotePreWriteRefusal) {}
                try {
                    adapter.reconcile(wrong)
                    fail("Readback cannot cross account scope either")
                } catch (_: IllegalStateException) {}
            }
            assertTrue("No network request made for mismatched identities", methods.isEmpty())
        }

    @Test fun metainfoIsCopiedAndActualHashIsRecheckedBeforeAnyRpc() =
        withFixture { adapter, approved, methods, _ ->
            val bytes = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
                "01234567890123456789ee").toByteArray()
            val original = bytes.copyOf()
            val scope = (approved.intent.target as UniversalAddTarget.AuthenticatedServer).accountScope
            val goodHash = TorrentMetainfoReader.preview(bytes).infoHashV1
            val frozen = UniversalAddSource.metainfo(bytes, goodHash)
            val accepted = ticket(scope, frozen)
            bytes.fill(0)
            assertArrayEquals(original, accepted.intent.source.metainfoForSubmission())
            val retrieved = accepted.intent.source.metainfoForSubmission()!!
            retrieved.fill(1)
            assertArrayEquals(original, accepted.intent.source.metainfoForSubmission())
            assertEquals(goodHash, adapter.planFor(accepted).expectedInfoHash)
            val mislabeled = ticket(scope, UniversalAddSource.metainfo(original, hash))
            try {
                adapter.submit(mislabeled)
                fail("Mislabeled metainfo must fail before the engine")
            } catch (_: RemotePreWriteRefusal) {}
            assertTrue(methods.isEmpty())
            assertFalse(accepted.toString().contains("test.txt"))
            assertFalse(accepted.toString().contains("0123456789"))
        }

    @Test fun hiddenMainViewCanStillConfirmExactStoppedSessionWithoutMutation() =
        withFixture(start = false, lostAck = true, hiddenFromMain = true) {
                adapter, approved, methods, reconnect ->
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
                adapter.submit(approved).result)
            val recovered = RemoteUniversalAddAdapter(reconnect()).reconcile(approved)
            assertEquals(
                RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, place)),
                recovered)
            val observed = requireNotNull(adapter.exactObservation(
                approved, recovered, newRegistrationVerified = false))
            assertEquals(false, observed.newRegistrationVerified)
            assertEquals(true, observed.reviewedPlaceVerified)
            assertEquals(false, observed.started)
            assertEquals(1, methods.count { it == "load.verbose" })
            assertFalse(methods.any { it == "d.start" || it == "load.start" })
        }

    @Test fun uncertainRecoveredSessionDoesNotClaimChunksOriginButCanBeAcknowledged() =
        withFixture(lostAck = true) { adapter, approved, methods, reconnect ->
            val attempted = adapter.submit(approved)
            assertNull(adapter.exactObservation(
                approved, attempted.result, newRegistrationVerified = true))
            val recovered = RemoteUniversalAddAdapter(reconnect()).reconcile(approved)
            val observation = requireNotNull(adapter.exactObservation(
                approved, recovered, newRegistrationVerified = false))
            val coordinator = UniversalAddCoordinator()
            val intent = approved.intent
            assertTrue(coordinator.review(intent,
                EnvironmentCapabilities.unprobed(intent.target.environment),
                EnvironmentAvailability.LIVE).accepted)
            assertTrue(coordinator.authorizeByUser())
            val sameIntentTicket = requireNotNull(coordinator.beginSubmission(1000L))
            assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
                coordinator.reconcileExactSession(sameIntentTicket, observation))
            assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, coordinator.phase)
            assertFalse(coordinator.finishVerified())
            assertTrue(coordinator.acknowledgeExactSession())
            assertEquals(UniversalAddCoordinator.Phase.EMPTY, coordinator.phase)
            assertEquals(1, methods.count { it == "load.start" })
        }

    @Test fun acknowledgedAddCanVerifySharedTicketFromExactEngineProof() =
        withFixture { adapter, approved, methods, _ ->
            val outcome = adapter.submit(approved).result
            val observation = requireNotNull(adapter.exactObservation(
                approved, outcome, newRegistrationVerified = true))
            val coordinator = UniversalAddCoordinator()
            val intent = approved.intent
            assertTrue(coordinator.review(intent,
                EnvironmentCapabilities.unprobed(intent.target.environment),
                EnvironmentAvailability.LIVE).accepted)
            assertTrue(coordinator.authorizeByUser())
            val sameIntentTicket = requireNotNull(coordinator.beginSubmission(1000L))
            assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
                coordinator.reconcileExactSession(sameIntentTicket, observation))
            assertTrue(coordinator.finishVerified())
            assertEquals(1, methods.count { it == "load.start" })
        }

    @Test fun mismatchedStoppedPlaceCannotManufactureExactSessionProof() =
        withFixture(start = false) { adapter, approved, methods, _ ->
            val wrong = RemoteAddAndStart.AddedStopped(
                RemoteAddOutcome.VerifiedStopped(hash, "/different"))
            assertNull(adapter.exactObservation(
                approved, wrong, newRegistrationVerified = true))
            assertTrue(methods.isEmpty())
        }

    @Test fun preWireAccountRefusalDisarmsOnlyThatTicketAndNeverReplaysIt() =
        withFixture { adapter, approved, methods, _ ->
            val badAccountScope = (approved.intent.target as
                UniversalAddTarget.AuthenticatedServer).accountScope + "other"
            val coordinator = UniversalAddCoordinator()
            val intent = UniversalAddIntent(
                source = approved.intent.source,
                target = UniversalAddTarget.AuthenticatedServer(badAccountScope),
                place = UniversalAddPlace.ServerCustom(place),
                startWhenAdded = true, allowArtwork = true)
            assertTrue(coordinator.review(intent,
                EnvironmentCapabilities.unprobed(intent.target.environment),
                EnvironmentAvailability.LIVE).accepted)
            assertTrue(coordinator.authorizeByUser())
            val firstTicket = requireNotNull(coordinator.beginSubmission(1000L))
            try {
                adapter.submit(firstTicket)
                fail("Wrong exact account/route cannot send load")
            } catch (_: RemotePreWriteRefusal) {}
            assertTrue("Known before-write rejection may release this exact ticket",
                coordinator.refusedBeforeAnyWrite(firstTicket))
            assertFalse("Previously refused ticket cannot be released twice",
                coordinator.refusedBeforeAnyWrite(firstTicket))
            assertNull("Refusal does not itself authorize another submission",
                coordinator.beginSubmission(1001L))
            assertEquals(UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
            assertTrue(coordinator.authorizeByUser())
            val newTicket = requireNotNull(coordinator.beginSubmission(1002L))
            assertNotSame(firstTicket, newTicket)
            assertTrue(methods.isEmpty())
        }

    @Test fun lostAckPeerlessMagnetCanAcknowledgeRegisteredPlaceWithoutClaimingStart() =
        withFixture(start = true, observedStarted = false, lostAck = true, hiddenFromMain = true) {
                adapter, approved, methods, reconnect ->
            assertTrue(adapter.submit(approved).result is RemoteAddAndStart.StartNotVerified)
            val recovery = RemoteUniversalAddAdapter(reconnect())
            assertTrue(recovery.reconcile(approved) is RemoteAddAndStart.StartNotVerified)
            val observed = requireNotNull(recovery.registeredJobWithUnverifiedStart(approved, 1200L))
            assertEquals(true, observed.registered)
            assertEquals(true, observed.reviewedPlaceVerified)
            assertNull("Peerless metadata cannot prove Start", observed.started)
            assertFalse("A lost reply cannot prove which client wrote the job", observed.newRegistrationVerified)

            val coordinator = UniversalAddCoordinator()
            val intent = approved.intent
            assertTrue(coordinator.review(intent,
                EnvironmentCapabilities.unprobed(intent.target.environment),
                EnvironmentAvailability.LIVE).accepted)
            assertTrue(coordinator.authorizeByUser())
            val ticket = requireNotNull(coordinator.beginSubmission(1000L))
            assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
                coordinator.reconcileExactSession(ticket, observed))
            assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, coordinator.phase)
            assertFalse(coordinator.acknowledgeExactSession())
            assertFalse(coordinator.finishVerified())
            assertTrue(coordinator.acknowledgeObservedJob())
            assertEquals(UniversalAddCoordinator.Phase.EMPTY, coordinator.phase)
            assertEquals(1, methods.count { it == "load.start" })
            assertTrue(methods.none { it == "d.start" || it == "load.verbose" || it == "d.resume" })
        }

    @Test fun wrongPlaceCannotUnlockRegisteredPeerlessRecovery() =
        withFixture(start = true, observedStarted = false, lostAck = true,
            observedDirectory = "/wrong-place") { adapter, approved, methods, reconnect ->
            assertTrue(adapter.submit(approved).result is RemoteAddAndStart.StartNotVerified)
            assertNull(RemoteUniversalAddAdapter(reconnect())
                .registeredJobWithUnverifiedStart(approved))
            assertEquals(1, methods.count { it == "load.start" })
            assertTrue(methods.none { it == "d.start" })
        }

    @Test fun slowerEarlierReadKeepsEarlierAcquisitionTimestampThanNewerCompletedRead() =
        runBlocking {
            val firstRequestEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstNameRead = AtomicBoolean(true)
            val executor = Executors.newFixedThreadPool(4)
            val server = HttpServer.create(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.executor = executor
            server.createContext("/RPC2") { exchange ->
                val xml = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                val response = when (method) {
                    "d.name" -> {
                        if (firstNameRead.compareAndSet(true, false)) {
                            firstRequestEntered.countDown()
                            // A read begun earlier deliberately returns last.
                            releaseFirst.await(10, TimeUnit.SECONDS)
                        }
                        value("Sample")
                    }
                    "d.hash" -> value(hash)
                    "d.directory" -> value(place)
                    "d.is_multi_file" -> number(0)
                    else -> throw AssertionError("Unexpected recovery RPC: $method")
                }
                respond(exchange, response)
            }
            server.start()
            try {
                val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
                val remote = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
                val approved = ticket(remote.addAccountScope())
                val adapter = RemoteUniversalAddAdapter(remote)
                val earlier = async(Dispatchers.IO) {
                    adapter.registeredJobWithUnverifiedStart(approved)
                }
                assertTrue("First read must enter the server",
                    firstRequestEntered.await(5, TimeUnit.SECONDS))
                // Different wall-clock milliseconds avoid conflating ties with
                // the model's future generation-order contract.
                delay(50L)
                val later = async(Dispatchers.IO) {
                    adapter.registeredJobWithUnverifiedStart(approved)
                }
                val newer = requireNotNull(withTimeout(9_000L) { later.await() })
                assertEquals(true, newer.reviewedPlaceVerified)
                releaseFirst.countDown()
                val older = requireNotNull(withTimeout(9_000L) { earlier.await() })
                assertEquals(true, older.reviewedPlaceVerified)
                assertTrue("Earlier started read finishing last must retain older order",
                    older.observedAtEpochMillis < newer.observedAtEpochMillis)
            } finally {
                releaseFirst.countDown()
                server.stop(0)
                executor.shutdownNow()
            }
        }


    @Test fun repeatedExactUnknownHashCanRevokeOnlyTheOriginalUnacknowledgedSession() =
        withFixture(initialPresent = false, hiddenFromMain = true) {
                adapter, ticket, methods, _ ->
            val observed = requireNotNull(
                adapter.contradictoryExactSession(ticket, observedAtEpochMillis = 2400L))
            assertEquals(ticket.intent.target.environment, observed.environment)
            assertEquals(hash, observed.infoHash)
            assertEquals(2400L, observed.observedAtEpochMillis)
            assertFalse(observed.registered)
            assertFalse(observed.reviewedPlaceVerified)
            assertNull(observed.started)
            assertFalse(observed.newRegistrationVerified)
            assertEquals(2, methods.count { it == "d.name" })
            assertEquals(2, methods.count { it == "d.hash" })
            assertTrue("No lost command was replayed", methods.all { it.startsWith("d.") })
        }

    @Test fun stableWrongPlaceCanRevokeAcknowledgmentWithoutNewAddOrStart() =
        withFixture(initialPresent = true, observedDirectory = "/other-server-folder",
            hiddenFromMain = true) { adapter, ticket, methods, _ ->
            val observed = requireNotNull(
                adapter.contradictoryExactSession(ticket, observedAtEpochMillis = 2500L))
            assertEquals(true, observed.registered)
            assertEquals(false, observed.reviewedPlaceVerified)
            assertNull(observed.started)
            assertFalse(observed.newRegistrationVerified)
            assertEquals(2500L, observed.observedAtEpochMillis)
            assertEquals(2, methods.count { it == "d.directory" })
            assertTrue("Only exact-job readbacks may contradict Place",
                methods.all { it.startsWith("d.") })
        }

    @Test fun singleTransientUnknownNameCannotRevokeExistingPositive() =
        withFixture(initialPresent = true, transientMissingNameOnce = true,
            observedDirectory = "/wrong-parent") { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertEquals(1, methods.count { it == "d.name" })
            assertEquals(1, methods.count { it == "d.hash" })
            assertTrue(methods.all { it.startsWith("d.") })
        }

    @Test fun validReviewedPlaceAndMetadataPromotionCannotContradict() {
        withFixture(initialPresent = true) { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
        withFixture(initialPresent = true, observedDirectory = "/wrong-parent",
            metadataPending = 1) { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
    }

    @Test fun malformedFlagOrUnstableNameAndDirectoryCannotDisproveReviewedPlace() {
        withFixture(initialPresent = true, observedDirectory = "/wrong-parent",
            multiFileFlag = 2) { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
        withFixture(initialPresent = true, observedDirectory = "/wrong-parent",
            observedDirectoryAfterFirstRead = "/other-parent") {
                adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertEquals(2, methods.count { it == "d.directory" })
            assertTrue(methods.all { it.startsWith("d.") })
        }
        withFixture(initialPresent = true, observedDirectory = "/wrong-parent",
            reportedHash = "cd".repeat(20)) { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
    }

    @Test fun transportAuthFailureAndInvalidRemotePlaceCannotFabricateContradiction() {
        withFixture(initialPresent = true, unauthorized = true,
            observedDirectory = "/wrong-parent") { adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
        withFixture(initialPresent = true, observedDirectory = "/wrong/../parent") {
                adapter, ticket, methods, _ ->
            assertNull(adapter.contradictoryExactSession(ticket))
            assertTrue(methods.all { it.startsWith("d.") })
        }
    }

    @Test fun wrongAuthenticatedRouteCannotEvenStartExactContradictionRead() =
        withFixture(initialPresent = true, observedDirectory = "/wrong-parent") {
                adapter, _, methods, _ ->
            val unrelated = ticket("different-authenticated-server")
            try {
                adapter.contradictoryExactSession(unrelated)
                fail("Wrong account cannot provide exact-job contrary evidence")
            } catch (_: IllegalStateException) {
                // No RPC and no model state changed.
            }
            assertTrue(methods.isEmpty())
        }

    @Test fun priorityModeCannotBeAppliedToMagnetOrStoppedOnlyAdd() =
        withFixture { adapter, approved, methods, _ ->
            try {
                adapter.submit(approved, prioritiesBeforeStart = true) { "priority" }
                fail("A magnet cannot claim metainfo priority support")
            } catch (_: RemotePreWriteRefusal) {}
            assertTrue(methods.isEmpty())
        }
}
