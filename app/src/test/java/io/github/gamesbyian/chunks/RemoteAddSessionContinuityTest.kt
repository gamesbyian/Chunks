package io.github.gamesbyian.chunks

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Negative/recovery presentation only; no real-engine acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteAddSessionContinuityTest {
    @get:Rule val rule = createComposeRule()
    private fun reveal(text: String) = rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))

    @Test fun uncertainAttemptCannotBecomeAnotherAddAfterNavigation() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)!!.groupValues[1]
            requests += method
            // A *post-load* lost reply is truly uncertain. A failure at
            // d.multicall2 before load is a proven pre-wire refusal and must
            // remain separately recoverable instead of pinning the ticket.
            if (method == "load.start") {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
                return@createContext
            }
            val reply = when (method) {
                "directory.default" ->
                    "<params><param><value><string>/srv</string></value></param></params>"
                "d.multicall2" ->
                    "<params><param><value><array><data></data></array></value></param></params>"
                "d.name" ->
                    "<fault><value><struct><member><name>faultCode</name><value><int>-501</int></value></member>" +
                    "<member><name>faultString</name><value><string>Could not find info-hash.</string></value></member></struct></value></fault>"
                else ->
                    "<fault><value><struct><member><name>faultCode</name><value><int>1</int></value></member>" +
                    "<member><name>faultString</name><value><string>Unavailable</string></value></member></struct></value></fault>"
            }
            val bytes = "<methodResponse>$reply</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
        val remote = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
        val showing = mutableStateOf(true)
        val source = mutableStateOf("magnet:?xt=urn:btih:" + "a".repeat(40) + "&tr=https%3A%2F%2Ftracker.example%2Fprivate-token")
        val session = RemoteAddSession(endpoint + "\u0000", "/srv")
        var attempts = 0
        var settled = 0
        try {
            rule.setContent { ChunksTheme { Surface {
                if (showing.value) VerifiedAddScreen(remote, source.value, "/srv", { source.value = it }, {},
                    onBack = { showing.value = false }, onComplete = {},
                    onMutationStarted = { attempts++; true }, onMutationSettled = { settled++ },
                    finishInOrderScope = endpoint + "\u0000", session = session)
            } } }
            reveal("Add transfer")
            
            reveal("Add transfer")
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
            rule.waitUntil(15_000) { settled == 1 }
            reveal("Check server state (read only)")
            reveal("Back to transfers")
            rule.onNodeWithText("Back to transfers").performClick()
            rule.runOnIdle { showing.value = true }
            reveal("Check server state (read only)")
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertDoesNotExist()
            reveal("Magnet or torrent link")
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).assertIsNotEnabled()
            reveal("Check server state (read only)")
            assertEquals(1, attempts)
            val before = requests.toList()
            rule.onNodeWithText("Check server state (read only)").performClick()
            rule.waitUntil(15_000) { requests.size > before.size }
            assertEquals(1, attempts)
            assertEquals("Exactly one originally authorized load, never replayed",
                1, requests.count { it == "load.start" })
            assertFalse(requests.any { it == "load.verbose" || it == "d.start" })
        } finally { server.stop(0) }
    }
    private val document = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray()

    @Test fun reviewedDocumentAndPlaceSurviveReturnWithoutSubmitting() {
        val endpoint = "http://127.0.0.1:9/RPC2"
        val account = endpoint + "\u0000owner"
        val session = RemoteAddSession(account, "/srv/reviewed")
        val incoming = mutableStateOf<ByteArray?>(document)
        val showing = mutableStateOf(true)
        val start = mutableStateOf(false)
        val artwork = mutableStateOf(false)
        val source = mutableStateOf("")
        var consumed = 0
        rule.setContent { ChunksTheme { Surface {
            if (showing.value) VerifiedAddScreen(
                RTorrentRemote(RemoteCredentials(endpoint, "owner", "")), source.value, session.destination,
                onMagnet = { source.value = it },
                onDestination = { session.destination = it }, onBack = { showing.value = false }, onComplete = {},
                onMutationStarted = { error("Review/navigation must not authorize Add") }, onMutationSettled = {},
                initialTorrent = incoming.value, onInitialTorrentConsumed = { incoming.value = null; consumed++ },
                finishInOrderScope = account, addStartState = start, addArtworkState = artwork, session = session)
        } } }
        reveal("Add transfer")
        
        reveal("Add transfer")
        rule.runOnIdle { showing.value = false }
        rule.runOnIdle { showing.value = true }
        reveal("test.txt")
        // The lazy form composes the submit action only when scrolled near it.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_SUBMIT))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertExists()
        reveal("Add transfer")
        
        reveal("Destination: /srv/reviewed")
        assertEquals(1, consumed)
        assertArrayEquals(document, session.selectedBytes)
        assertFalse(start.value)
        assertFalse(artwork.value)
        rule.runOnIdle { showing.value = false }
        rule.runOnIdle {
            source.value = "magnet:?xt=urn:btih:" + "b".repeat(40) + "&dn=EditedOnPhone"
            showing.value = true
        }
        reveal("Magnet for: EditedOnPhone")
        assertFalse("New shared text selects the new source instead of an old file", session.useFile)
        assertArrayEquals(document, session.selectedBytes)
        // The old file is still an available explicit alternative, not default.
        reveal(".torrent file")
        rule.onNodeWithText(".torrent file").performClick()
        rule.runOnIdle { showing.value = false }
        rule.runOnIdle { showing.value = true }
        reveal("Add transfer")
        assertTrue("An explicit file choice is retained on ordinary return", session.useFile)
    }

    @Test fun lostMutationReplyAndSameAccountReconnectKeepFrozenReadOnlyRecovery() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val oldHash = "ab".repeat(20)
        var loaded = false
        server.createContext("/RPC2") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return@createContext
            }
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)!!.groupValues[1]
            calls += method to xml
            if (method == "load.start") {
                loaded = true // Model application before a lost gateway reply.
                received.countDown()
                release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                val value = when (method) {
                    "directory.default", "d.directory" -> "<string>/srv</string>"
                    "d.multicall2" -> "<array><data></data></array>"
                    "d.name" -> "<string>Original</string>"
                    "d.hash" -> "<string>$oldHash</string>"
                    "d.is_multi_file" -> "<int>0</int>"
                    "d.state", "d.is_active" -> "<int>1</int>"
                    else -> "<int>0</int>"
                }
                val reply = if (method == "d.name" && !loaded)
                    "<fault><value><struct><member><name>faultCode</name><value><int>-501</int></value></member>" +
                    "<member><name>faultString</name><value><string>Could not find info-hash.</string></value></member></struct></value></fault>"
                else "<params><param><value>$value</value></param></params>"
                val bytes = "<methodResponse>$reply</methodResponse>".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
        val account = endpoint + "\u0000owner"
        val session = RemoteAddSession(account, "/srv")
        val remote = mutableStateOf(RTorrentRemote(RemoteCredentials(endpoint, "owner", "")))
        val showing = mutableStateOf(true)
        val source = mutableStateOf("magnet:?xt=urn:btih:$oldHash&dn=Original")
        val start = mutableStateOf(true)
        val artwork = mutableStateOf(true)
        val incoming = mutableStateOf<ByteArray?>(null)
        var settled = 0
        var attempts = 0
        var verified = 0
        var consumed = 0
        try {
            rule.setContent { ChunksTheme { Surface {
                if (showing.value) VerifiedAddScreen(remote.value, source.value, session.destination,
                    onMagnet = { source.value = it }, onDestination = { session.destination = it },
                    onBack = { showing.value = false }, onComplete = {}, onVerifiedAdd = { verified++ },
                    onMutationStarted = { attempts++; true }, onMutationSettled = { settled++ },
                    initialTorrent = incoming.value, onInitialTorrentConsumed = { incoming.value = null; consumed++ },
                    finishInOrderScope = account, addStartState = start, addArtworkState = artwork, session = session)
            } } }
            reveal("Add transfer")
            
            reveal("Add transfer")
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
            try {
                rule.waitUntil(15_000) { received.count == 0L }
            } catch (failure: Throwable) {
                throw AssertionError("Expected one load.start; observed ${calls.map { it.first }}; ${session.outcome}", failure)
            }
            rule.runOnIdle { showing.value = false }
            release.countDown()
            rule.waitUntil(15_000) { settled == 1 }
            val nextSource = "magnet:?xt=urn:btih:" + "cd".repeat(20) + "&dn=Next"
            rule.runOnIdle {
                source.value = nextSource
                start.value = false
                artwork.value = false
                incoming.value = document
                remote.value = RTorrentRemote(RemoteCredentials(endpoint, "owner", "new-login"))
                showing.value = true
            }
            reveal("Check server state (read only)")
            assertEquals(0, consumed)
            assertEquals(oldHash, session.attemptTicket?.intent?.source?.expectedInfoHash)
            assertTrue(session.attemptTicket?.intent?.startWhenAdded == true)
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertDoesNotExist()
            rule.onNodeWithText("Check server state (read only)").performClick()
            rule.waitUntil(15_000) {
                session.coordinator.phase ==
                    io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED
            }
            assertEquals("Recovered state cannot prove which client originally registered it", 0, verified)
            assertFalse("Late old completion must preserve the newer exclusion", artwork.value)
            reveal("Back to transfers")
            rule.onNodeWithText("Back to transfers").performClick()
            rule.runOnIdle { artwork.value = false; showing.value = true }
            // Session confirmation verifies exact hash/Place/Start but not
            // registration provenance. The UI must expose an independent,
            // explicit acknowledgement instead of allowing a generic new Add
            // to clear any uncertain write latch. Scroll to the exact button,
            // not similarly worded lazy-list guidance above it.
            rule.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Acknowledge session and start another Add"))
            assertEquals("Remount must not claim recovered registration as Chunks' new Add", 0, verified)
            assertFalse("Remount must not erase another draft's metadata choice", artwork.value)
            rule.onNodeWithText("Acknowledge session and start another Add")
                .assertIsEnabled().performClick()
            rule.waitUntil(15_000) { consumed == 1 }
            reveal("test.txt")
            assertEquals(nextSource, source.value)
            assertFalse(artwork.value)
            assertNull(session.attemptTicket)
            assertFalse(session.alreadyAttempted)
            assertEquals(1, attempts)
            assertEquals(1, calls.count { it.first == "load.start" })
            assertFalse(calls.any { it.first in setOf("d.start", "d.resume", "load.raw_start") })
            assertTrue(calls.filter { it.first == "d.hash" }.all { it.second.contains(oldHash) })
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun registeredPeerlessJobRequiresExplicitUserAcknowledgementBeforeNextRemoteAdd() {
        val endpoint = "http://127.0.0.1:9/RPC2"
        val account = endpoint + "\u0000"
        val oldHash = "ab".repeat(20)
        val oldSource = "magnet:?xt=urn:btih:$oldHash"
        val newSource = mutableStateOf("magnet:?xt=urn:btih:" + "cd".repeat(20))
        val session = RemoteAddSession(account, "/srv")
        val target = io.github.gamesbyian.chunks.model.UniversalAddTarget.AuthenticatedServer(account)
        val intent = io.github.gamesbyian.chunks.model.UniversalAddIntent(
            io.github.gamesbyian.chunks.model.UniversalAddSource.magnet(oldSource, oldHash),
            target, io.github.gamesbyian.chunks.model.UniversalAddPlace.ServerCustom("/srv"),
            startWhenAdded = true, allowArtwork = true)
        assertTrue(session.coordinator.review(intent,
            io.github.gamesbyian.chunks.model.EnvironmentCapabilities.unprobed(target.environment),
            io.github.gamesbyian.chunks.model.EnvironmentAvailability.LIVE).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(1000L))
        session.coordinator.reconcileExactSession(ticket,
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.ExactSessionObservation(
                target.environment, oldHash, 1001L, true, true, null, false))
        session.attemptTicket = ticket
        session.attemptSource = oldSource
        session.alreadyAttempted = true
        session.uncertain = true
        var mutationCalls = 0
        rule.setContent { ChunksTheme { Surface {
            VerifiedAddScreen(RTorrentRemote(RemoteCredentials(endpoint, "", "")),
                newSource.value, "/srv", { newSource.value = it }, {},
                onBack = {}, onComplete = {},
                onMutationStarted = { mutationCalls++; true }, onMutationSettled = {},
                finishInOrderScope = account, session = session)
        } } }
        reveal("Acknowledge registered job; Start unverified")
        rule.onNodeWithText("Acknowledge registered job; Start unverified").assertIsEnabled()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertDoesNotExist()
        assertEquals(io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        rule.onNodeWithText("Acknowledge registered job; Start unverified").performClick()
        rule.waitForIdle()
        assertEquals(io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.EMPTY,
            session.coordinator.phase)
        assertFalse(session.alreadyAttempted)
        assertNull(session.attemptTicket)
        assertEquals("New shared source must be preserved", "cd".repeat(20),
            newSource.value.substringAfter("urn:btih:"))
        assertEquals("Explicit acknowledgement did not authorize another RPC", 0, mutationCalls)
        reveal("Add transfer")
    }

    @Test(expected = IllegalArgumentException::class)
    fun anotherOpaqueAccountCannotBorrowAttemptedReview() {
        val endpoint = "http://127.0.0.1:9/RPC2"
        val session = RemoteAddSession(endpoint + "\u0000owner", "/srv")
        session.alreadyAttempted = true
        session.uncertain = true
        rule.setContent { ChunksTheme { Surface {
            VerifiedAddScreen(RTorrentRemote(RemoteCredentials(endpoint, "OWNER", "")), "", "/srv", {}, {},
                onBack = {}, onComplete = {}, onMutationStarted = { error("Wrong account must not mutate") },
                onMutationSettled = {}, finishInOrderScope = endpoint + "\u0000OWNER", session = session)
        } } }
    }

    @Test fun existingHashPreflightKeepsTheDraftCorrectableWithoutSendingAWrite() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)!!.groupValues[1]
            calls += method
            val value = when (method) {
                "directory.default" -> "<string>/srv</string>"
                "d.multicall2" -> "<array><data></data></array>"
                "d.name" -> "<string>Existing transfer</string>"
                else -> "<int>0</int>"
            }
            val bytes = "<methodResponse><params><param><value>$value</value></param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/RPC2"
        val account = endpoint + "\u0000"
        val session = RemoteAddSession(account, "/srv")
        val source = mutableStateOf("magnet:?xt=urn:btih:" + "a".repeat(40))
        var settled = 0
        try {
            val remote = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            rule.setContent { ChunksTheme { Surface {
                VerifiedAddScreen(remote, source.value, session.destination, { source.value = it }, {}, {}, {},
                    onMutationStarted = { true }, onMutationSettled = { settled++ },
                    finishInOrderScope = account, session = session)
            } } }
            reveal("Add transfer")
            
            reveal("Add transfer")
            rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performClick()
            rule.waitUntil(15_000) { settled == 1 }
            assertFalse("An existing-hash preflight sent no mutation; the user may correct the draft", session.alreadyAttempted)
            reveal("Magnet or torrent link")
            rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).assertIsEnabled()
            assertFalse(calls.any { it.startsWith("load.") || it in setOf("d.start", "d.resume") })
        } finally { server.stop(0) }
    }

}
