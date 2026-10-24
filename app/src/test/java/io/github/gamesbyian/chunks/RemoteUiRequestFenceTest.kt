package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class RemoteUiRequestFenceTest {
    @Test fun capturesTheRequestedViewAtDispatch() {
        val gate = RemoteUiRequestFence()
        val request = gate.begin("main")
        var draftView = "main"
        draftView = "started"
        assertEquals("main", request.view)
        assertEquals("started", draftView)
        assertTrue(gate.current(request))
    }

    @Test fun invalidationRejectsLateResultsAfterDisconnect() {
        val gate = RemoteUiRequestFence()
        val oldRead = gate.begin("main")
        gate.invalidate()
        val freshConnection = gate.begin("main")
        assertFalse("A slow prior-session HTTP reply must be ignored", gate.current(oldRead))
        assertTrue(gate.current(freshConnection))
    }

    @Test fun onlyNewestFileInspectorMayPublishData() {
        val gate = RemoteUiRequestFence()
        val first = gate.begin("main")
        val second = gate.begin("main")
        assertFalse("An older file result cannot overwrite selection", gate.current(first))
        assertTrue(gate.current(second))
        gate.invalidate()
        assertFalse("Collapsing a file card cancels its response", gate.current(second))
    }

    @Test fun refreshRequestsCoalesceWithoutBeingDropped() {
        val gate = RemoteUiRequestFence()
        val first = gate.begin("main")
        gate.queueRefresh()
        gate.queueRefresh()
        assertTrue(gate.current(first))
        assertTrue("A mutation after an in-flight manual read must get a fresh read",
            gate.consumeQueuedRefresh())
        assertFalse("Coalesced requests should trigger exactly one extra read",
            gate.consumeQueuedRefresh())
        val second = gate.begin("started")
        assertEquals("started", second.view)
        assertTrue(gate.current(second))
        assertFalse(gate.current(first))
    }

    @Test fun disconnectDropsQueuedRefreshes() {
        val gate = RemoteUiRequestFence()
        val request = gate.begin("main")
        gate.queueRefresh()
        gate.invalidate()
        assertFalse(gate.current(request))
        assertFalse(gate.consumeQueuedRefresh())
    }
}
