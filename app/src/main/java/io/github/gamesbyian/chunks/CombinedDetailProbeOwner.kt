package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RemoteProbe
import io.github.gamesbyian.chunks.rpc.RemoteStallEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Screen-lifetime owner of two independently supersedable, read-only probes.
 * Call bind/request and observe the results on the screen's main dispatcher.
 * A rendered callback carries the immutable binding that offered it, never
 * a freshly issued ticket for an old RPC lambda. Reconnect, focus, privacy
 * epoch and freshness changes invalidate even a binding that later reappears.
 */
internal class CombinedDetailProbeOwner(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    internal class Binding internal constructor(
        val session: Any,
        val environment: EnvironmentId,
        val focus: UnifiedTransferKey,
        val privacyEpoch: String
    )

    private var binding: Binding? = null
    private val capability = Probe<EnvironmentCapabilitySnapshot>()
    private val stall = Probe<RemoteStallEvidence>()

    fun bind(
        session: Any?,
        environment: EnvironmentId?,
        focus: UnifiedTransferKey?,
        authorized: Boolean,
        privacyEpoch: String
    ): Binding? {
        val previous = binding
        val valid = authorized && session != null && environment != null &&
            focus != null && focus.environment == environment
        if (valid && previous != null && previous.session === session &&
            previous.environment == environment && previous.focus == focus &&
            previous.privacyEpoch == privacyEpoch) return previous
        if (previous != null || valid) {
            capability.gate.invalidate()
            stall.gate.invalidate()
        }
        val next = if (valid) Binding(requireNotNull(session), requireNotNull(environment),
            requireNotNull(focus), privacyEpoch) else null
        binding = next
        capability.gate.bind(next?.session, next?.environment, next?.focus)
        stall.gate.bind(next?.session, next?.environment, next?.focus)
        return next
    }

    private class Probe<T> {
        val gate = RemoteCapabilityProbeSessionGate()
        var pending by mutableStateOf<RemoteCapabilityProbeSessionGate.Ticket?>(null)
        var evidence by mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, T>?>(null)
        var failure by mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, String>?>(null)
    }

    private fun <T> current(probe: Probe<T>, ticket: RemoteCapabilityProbeSessionGate.Ticket?): Boolean {
        val active = binding ?: return false
        return ticket != null && probe.gate.current(ticket, active.session, active.environment, active.focus)
    }

    val capabilitySnapshot get() = capability.evidence?.takeIf { current(capability, it.first) }?.second
    val capabilityInProgress get() = current(capability, capability.pending)
    val capabilityError get() = capability.failure?.takeIf { current(capability, it.first) }?.second
    val stallReport get() = stall.evidence?.takeIf { current(stall, it.first) }?.second
    val stallInProgress get() = current(stall, stall.pending)
    val stallError get() = stall.failure?.takeIf { current(stall, it.first) }?.second

    fun requestCapabilities(rendered: Binding?, probe: suspend () -> RemoteProbe) {
        request(rendered, capability,
            "The client capability check timed out; no methods were verified.",
            "The signed-in remote client did not complete the read-only check.") { ticket ->
            EnvironmentCapabilities.fromProbe(ticket.environment, probe(), System.currentTimeMillis())
        }
    }

    fun requestStall(rendered: Binding?, inspect: suspend (String) -> RemoteStallEvidence) {
        request(rendered, stall,
            "The exact transfer check timed out. Its current state is unknown.",
            "The signed-in remote client could not finish the exact transfer check.") { ticket ->
            inspect(ticket.focus.infoHash)
        }
    }

    private fun <T> request(
        rendered: Binding?,
        state: Probe<T>,
        timeoutMessage: String,
        failureMessage: String,
        read: suspend (RemoteCapabilityProbeSessionGate.Ticket) -> T
    ) {
        // Reject an old callback BEFORE beginning a request: it must not
        // supersede an already pending request on the newly bound client.
        if (rendered == null || rendered !== binding) return
        val ticket = state.gate.begin() ?: return
        if (!current(state, ticket)) return
        state.pending = ticket
        state.evidence = null
        state.failure = null
        scope.launch {
            try {
                if (!current(state, ticket)) return@launch
                val observed = withContext(io) {
                    withTimeout(15_000L) { read(ticket) }
                }
                if (current(state, ticket)) state.evidence = ticket to observed
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                if (current(state, ticket)) state.failure = ticket to timeoutMessage
            } catch (_: Exception) {
                // Fixed copy only: RPC errors may contain private paths,
                // tracker URLs or credentials.
                if (current(state, ticket)) state.failure = ticket to failureMessage
            } finally {
                if (state.pending === ticket) state.pending = null
            }
        }
    }
}
