package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
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
        body: suspend (
            RemoteUniversalAddAdapter, UniversalAddCoordinator.Ticket,
            MutableList<String>, () -> RTorrentRemote
        ) -> Unit
    ) = runBlocking {
        var loaded = initialPresent
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
            if (method == "d.name" && !loaded) {
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
                "d.multicall2" -> if (loaded && !hiddenFromMain) row(if (start) 1 else 0, observedDirectory)
                    else "<value><array><data></data></array></value>"
                "d.name" -> value("Sample")
                "d.hash" -> value(hash)
                "d.directory" -> value(observedDirectory)
                "d.is_multi_file" -> number(0)
                "d.state", "d.is_active" -> number(if (start) 1 else 0)
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

    @Test fun priorityModeCannotBeAppliedToMagnetOrStoppedOnlyAdd() =
        withFixture { adapter, approved, methods, _ ->
            try {
                adapter.submit(approved, prioritiesBeforeStart = true) { "priority" }
                fail("A magnet cannot claim metainfo priority support")
            } catch (_: RemotePreWriteRefusal) {}
            assertTrue(methods.isEmpty())
        }
}
