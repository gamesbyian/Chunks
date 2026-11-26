package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A2611-02: an authenticated rTorrent session survives Activity recreation
 * without reconnecting, and an uncertain write's readback latch survives with
 * it: writes stay refused until one fresh accepted read happens.
 * Loopback fixture only; no real-engine acceptance claim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class RemoteSessionRecreationTest {
    @get:Rule val rule = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    /** When set, the next list read is held until released. */
    @Volatile private var holdNextRead: java.util.concurrent.CountDownLatch? = null
    @Volatile private var readHeld: java.util.concurrent.CountDownLatch? = null
    private lateinit var fixture: com.sun.net.httpserver.HttpServer
    private val endpoint get() = "http://127.0.0.1:${fixture.address.port}/RPC2"

    @Before fun startFixture() {
        fixture = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
            requests += method
            if (method == "d.multicall2") holdNextRead?.let { gate ->
                holdNextRead = null
                readHeld?.countDown()
                gate.await(15, java.util.concurrent.TimeUnit.SECONDS)
            }
            val reply = when (method) {
                "system.client_version" -> "<params><param><value><string>0.9.8</string></value></param></params>"
                "d.multicall2" -> "<params><param><value><array><data></data></array></value></param></params>"
                else -> "<fault><value><struct><member><name>faultCode</name><value><int>1</int></value></member>" +
                    "<member><name>faultString</name><value><string>Unavailable</string></value></member></struct></value></fault>"
            }
            val bytes = "<methodResponse>$reply</methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fixture.start()
    }

    @After fun stopFixture() = fixture.stop(0)

    private fun connect(
        restoration: StateRestorationTester,
        session: RemoteSessionOwner,
        owner: LocalAddAttemptOwner = LocalAddAttemptOwner()
    ) {
        restoration.setContent { ChunksTheme { ChunksApp(owner, session) } }
        rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
        rule.openConnectionFormIfNeeded(allowConnected = false)
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").performTextInput(endpoint)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(ChunksSemantics.CONNECTION_CONNECT))
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_CONNECT).performClick()
        rule.waitUntil(15_000) { session.server.value != null }
        rule.waitForIdle()
    }

    @Test fun recreationKeepsTheAuthenticatedSessionWithoutReconnecting() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        val remote = assertNotNull(session.server.value).let { session.server.value }
        val connects = requests.count { it == "system.client_version" }
        val scope = session.aliasScope.value
        assertTrue("connected to an exact account", scope.isNotEmpty())

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertSame("the same live session, not a new login", remote, session.server.value)
        assertEquals(scope, session.aliasScope.value)
        assertEquals("no reconnect after recreation", connects, requests.count { it == "system.client_version" })
        rule.onNodeWithText("Finding secure rTorrent…").assertDoesNotExist()
    }

    @Test fun postAddFocusAndServerHandoffSurviveRotationInsideAnUnlockedWindow() {
        val session = RemoteSessionOwner()
        val owner = LocalAddAttemptOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session, owner)
        val remote = requireNotNull(session.server.value)
        val scope = session.aliasScope.value
        val hash = "e".repeat(40)
        val record = RemoteAddedRecord(scope, hash, "main|ALL")
        rule.runOnIdle {
            owner.postAdd.rememberRemote(remote, record)
            owner.handoff.stage("magnet:?xt=urn:btih:$hash", RemoteSessionStamp(remote, scope))
        }
        // A real rotation also stops and restarts the Activity.
        rule.runOnIdle {
            io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime.onBackground()
            io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime.onForeground()
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(record, owner.postAdd.remoteFor(session.server.value, session.aliasScope.value))
        assertNotNull("the focused key is shown for this exact session",
            owner.postAdd.latestFor(session.server.value, session.aliasScope.value))
    }

    @Test fun disconnectingClearsPostAddFocusAndTheServerHandoff() {
        val session = RemoteSessionOwner()
        val owner = LocalAddAttemptOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session, owner)
        val remote = requireNotNull(session.server.value)
        val scope = session.aliasScope.value
        val hash = "e".repeat(40)
        rule.runOnIdle {
            owner.postAdd.rememberRemote(remote, RemoteAddedRecord(scope, hash, "main|ALL"))
            owner.handoff.stage("magnet:?xt=urn:btih:$hash", RemoteSessionStamp(remote, scope))
            // What Disconnect, Forget-then-disconnect and a profile switch all do to the owner.
            session.server.value = null
            session.aliasScope.value = ""
        }
        // A state change made outside a frame needs one frame to recompose here.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.waitUntil(15_000) { owner.postAdd.remote == null }
        rule.waitUntil(15_000) { owner.handoff.staged == null }
        assertEquals(null, owner.postAdd.latestFor(remote, scope))
    }

    /**
     * A2611-06 baseline: with no policy or seeding rule enabled, the connected app's
     * 30-second policy ticks send no RPC at all. Five virtual minutes (ten ticks) leave the
     * request log unchanged: the idle cost is local preference reads only.
     */
    @Test fun idlePolicyTicksSendNoRpcWhenNoRuleIsEnabled() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        rule.waitForIdle()
        val before = requests.toList()
        assertTrue("the connection itself read the inventory", before.contains("d.multicall2"))
        rule.mainClock.advanceTimeBy(300_000)
        rule.waitForIdle()
        assertEquals("ten idle policy ticks sent no RPC", before, requests.toList())
    }

    /** Positive control for the idle test: the same virtual ticks do act once a rule is enabled. */
    @Test fun anEnabledSeedingRuleMakesTheSameTicksTalkToTheServer() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        rule.waitForIdle()
        val remote = requireNotNull(session.server.value)
        val environment = requireNotNull(io.github.gamesbyian.chunks.seeding.RemoteSeeding.environmentOf(remote))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(io.github.gamesbyian.chunks.seeding.RemoteSeeding.EnforcementSwitch(context).set(environment, true))
        val before = requests.size
        rule.mainClock.advanceTimeBy(65_000)
        rule.waitUntil(15_000) { requests.size > before }
        assertTrue("an enabled rule reads the server on its tick", requests.size > before)
    }

    @Test fun uncertainWriteStaysLatchedAcrossRecreationUntilOneFreshRead() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        val gate = session.mutationGate
        // A write was dispatched and settled without a readback (its
        // composition's refresh never ran): exactly the uncertain state.
        rule.runOnIdle {
            assertTrue(gate.tryStart())
            gate.settled()
            session.stale.value = true
        }
        assertFalse("no second write before a fresh read", gate.canStart)
        val reads = requests.count { it == "d.multicall2" }

        restoration.emulateSavedInstanceStateRestore()

        assertSame("the readback gate is retained with the session", gate, session.mutationGate)
        rule.waitUntil(15_000) { requests.count { it == "d.multicall2" } > reads && gate.canStart }
        assertFalse(session.stale.value)
        assertEquals("exactly one fresh read releases the latch",
            reads + 1, requests.count { it == "d.multicall2" })
        assertEquals("no write RPC was ever sent", emptyList<String>(),
            requests.filterNot { it == "system.client_version" || it == "d.multicall2" })

        // A write settled through a disposed composition only signals; the
        // live composition performs the read.
        rule.runOnIdle {
            assertTrue(gate.tryStart())
            gate.settled()
            session.stale.value = true
            session.requestReadback()
        }
        // A write made outside a frame needs one frame to recompose here.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        try {
            rule.waitUntil(15_000) { requests.count { it == "d.multicall2" } >= reads + 2 && gate.canStart }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("reads=${requests.count { it == "d.multicall2" } - reads} canStart=${gate.canStart} " +
                "stale=${session.stale.value} requests=$requests", timeout)
        }
        assertEquals("one signalled read", reads + 2, requests.count { it == "d.multicall2" })
    }

    @Test fun aWriteSettledWhileAReadIsInFlightGetsAnotherRead() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        val gate = session.mutationGate
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.CountDownLatch(1)
        holdNextRead = release
        readHeld = held
        val reads = requests.count { it == "d.multicall2" }
        // A write is dispatched; an unrelated read then starts and is held.
        rule.runOnIdle {
            assertTrue(gate.tryStart())
            session.stale.value = true
            session.requestReadback()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("a read is in flight", held.await(15, java.util.concurrent.TimeUnit.SECONDS))
        // The write settles while that read is in flight, signalling only (as
        // through a disposed composition). The held read's ticket predates the
        // settle, so it cannot release the latch: another read must follow.
        rule.runOnIdle {
            gate.settled()
            session.stale.value = true
            session.requestReadback()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        release.countDown()
        try {
            rule.waitUntil(15_000) { requests.count { it == "d.multicall2" } >= reads + 2 && gate.canStart }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("reads=${requests.count { it == "d.multicall2" } - reads} canStart=${gate.canStart} " +
                "stale=${session.stale.value}", timeout)
        }
        assertEquals("the pre-settle read plus exactly one queued fresh read",
            reads + 2, requests.count { it == "d.multicall2" })
        assertFalse(session.stale.value)
    }

    @Test fun aReadCancelledByRecreationNeverMarksTheRetainedSessionStale() {
        val session = RemoteSessionOwner()
        val restoration = StateRestorationTester(rule)
        connect(restoration, session)
        assertFalse(session.stale.value)
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.CountDownLatch(1)
        holdNextRead = release
        readHeld = held
        // Returning to Transfers re-reads the server while the list is not stale.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        assertTrue("a read is in flight", held.await(15, java.util.concurrent.TimeUnit.SECONDS))
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val reads = requests.count { it == "d.multicall2" }
        // The disposed composition's read now completes into a cancelled coroutine.
        release.countDown()
        Thread.sleep(500)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertFalse("a cancelled read must not mark the retained session stale", session.stale.value)
        assertTrue("writes are still admitted", session.mutationGate.canStart)
        assertEquals("no extra read was needed", reads, requests.count { it == "d.multicall2" })
    }
}
