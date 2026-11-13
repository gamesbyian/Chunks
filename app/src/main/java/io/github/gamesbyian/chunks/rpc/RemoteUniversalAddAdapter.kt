package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator.ExactSessionObservation
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget

/**
 * A known refusal before RTorrentRemote could issue any non-idempotent load.
 * Unlike a lost write acknowledgement, this can release the shared ticket.
 * Never expose raw transport/authentication errors or private paths in UI.
 */
internal class RemotePreWriteRefusal : IllegalStateException(
    "rTorrent preflight failed before any Add command"
)

/** Result of the existing rTorrent Add operations, with optional file-priority feedback. */
internal class RemoteAddExecution(val result: RemoteAddAndStart, val orderNote: String?)

/**
 * rTorrent port for the shared, process-only Universal Add submission ticket.
 * This class holds no draft, mutable attempt flag, client credentials or queued
 * executor. The owning coordinator must authorize once, mint a ticket before
 * suspending, and retain its write-attempt latch through uncertain outcomes.
 *
 * All writes and readbacks remain in the existing RTorrentRemote adapter.
 * Reconciliation here is exclusively read-only, including a lost Start reply
 * after stopped-first file-priority setup.
 */
internal class RemoteUniversalAddAdapter(private val remote: RTorrentRemote) {
    /**
     * Reconstruct the engine-validated plan from the ticket's frozen source.
     * Verifying the actual metainfo hash here closes the shared model's
     * deliberately protocol-neutral (caller-validated) metainfo boundary.
     * Never use a filename, navigation draft or different connected account.
     */
    fun planFor(ticket: UniversalAddCoordinator.Ticket): StoppedRemoteAddPlan {
        val intent = ticket.intent
        val account = intent.target as? UniversalAddTarget.AuthenticatedServer
            ?: throw IllegalArgumentException("Remote Add requires an authenticated server target")
        check(account.accountScope == remote.addAccountScope()) {
            "The rTorrent account or exact numbered route changed; reconnect before Add or readback"
        }
        val (destination, serverDefault) = when (val place = intent.place) {
            is UniversalAddPlace.ServerCustom -> place.absolutePath to false
            is UniversalAddPlace.ServerDefault -> place.observedDefaultPath to true
            else -> throw IllegalArgumentException("Remote Add requires a server Place")
        }
        val source = intent.source
        val link = when (source.kind) {
            UniversalAddSource.Kind.MAGNET -> requireNotNull(source.magnetForSubmission()) {
                "Expected reviewed magnet source"
            }
            UniversalAddSource.Kind.TORRENT_METAINFO -> {
                val bytes = requireNotNull(source.metainfoForSubmission()) {
                    "Expected reviewed torrent bytes"
                }
                val actualHash = TorrentMetainfoReader.preview(bytes).infoHashV1
                require(actualHash.equals(source.expectedInfoHash, ignoreCase = true)) {
                    "Reviewed metainfo changed identity"
                }
                "magnet:?xt=urn:btih:$actualHash"
            }
        }
        val plan = if (serverDefault)
            StoppedRemoteAddPlan.fromMagnetAtServerDefault(link, destination)
        else StoppedRemoteAddPlan.fromMagnet(link, destination)
        require(plan.expectedInfoHash.equals(source.expectedInfoHash, ignoreCase = true)) {
            "Reviewed source and target identity disagree"
        }
        return plan
    }

    /**
     * Exactly one Add dispatch under the caller's already-attempted ticket.
     * For ordinary Start the rTorrent-native load.start/load.raw_start avoids
     * a second mutation; priority-before-Start necessarily uses stopped load
     * followed by existing priority and Start code, never for an existing hash.
     */
    suspend fun submit(
        ticket: UniversalAddCoordinator.Ticket,
        prioritiesBeforeStart: Boolean = false,
        onVerifiedStoppedBeforeStart: (suspend (String) -> String?)? = null
    ): RemoteAddExecution {
        // This entire block is pure local validation. The ticket has been
        // acquired, but no network request or non-idempotent load can have
        // happened yet. Refuse specifically as known-before-wire, so the
        // coordinator may release *only this* attempted ticket. In contrast,
        // all exceptions after load was possibly sent retain its write fence.
        val plan = try {
            planFor(ticket)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            throw RemotePreWriteRefusal()
        }
        val intent = ticket.intent
        if (prioritiesBeforeStart && !(
                intent.startWhenAdded &&
                intent.source.kind == UniversalAddSource.Kind.TORRENT_METAINFO &&
                onVerifiedStoppedBeforeStart != null))
            throw RemotePreWriteRefusal()
        val bytes = intent.source.metainfoForSubmission()
        // RTorrentRemote catches any failure *after* a load command could
        // have been sent and returns Uncertain/StartNotVerified. Exceptions
        // escaping these load methods are preflight refusals: no load was sent.
        // Cancellation is never a known no-write result.
        suspend fun loadStoppedPreflighted(): RemoteAddOutcome = try {
            if (bytes == null) remote.loadStopped(plan)
            else remote.loadStoppedTorrentBytes(bytes, plan.destination, plan.useServerDefault)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            throw RemotePreWriteRefusal()
        }
        if (intent.startWhenAdded && !prioritiesBeforeStart) {
            val outcome = try {
                if (bytes == null) remote.loadStarted(plan)
                else remote.loadStartedTorrentBytes(bytes, plan.destination, plan.useServerDefault)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                throw RemotePreWriteRefusal()
            }
            return RemoteAddExecution(outcome, null)
        }
        var note: String? = null
        val result = RemoteAddStarter.run(
            startAfterVerifiedAdd = intent.startWhenAdded,
            add = {
                val added = loadStoppedPreflighted()
                if (prioritiesBeforeStart && added is RemoteAddOutcome.VerifiedStopped)
                    note = requireNotNull(onVerifiedStoppedBeforeStart).invoke(added.hash)
                added
            },
            start = { hash -> remote.setStarted(hash, true) }
        )
        return RemoteAddExecution(result, note)
    }

    /**
     * Always reconcile the original frozen Start intent, never the UI's
     * current checkbox or the provisional stopped phase of priority setup.
     * This issues only d.* getters / main inventory, never load.*, d.start or
     * priority mutations. An observed result cannot prove which client wrote
     * an uncertain Add, so the shared coordinator retains its own proof gate.
     */
    suspend fun reconcile(ticket: UniversalAddCoordinator.Ticket): RemoteAddAndStart {
        val plan = planFor(ticket)
        return if (ticket.intent.startWhenAdded) remote.reconcileStartedAdd(plan)
        else RemoteAddAndStart.AddedStopped(remote.reconcileStoppedAdd(plan))
    }

    /**
     * Translate a genuinely verified engine result into Agent 1's existing
     * exact-session observation model. This adds no read or mutation.
     * A successful submitted load is attributable to this reviewed command;
     * a later read-only recovery after timeout proves state but never origin.
     * In particular, do not convert AlreadyPresent, Pending or uncertain
     * outcomes into new-registration evidence.
     */
    suspend fun exactObservation(
        ticket: UniversalAddCoordinator.Ticket,
        verified: RemoteAddAndStart,
        newRegistrationVerified: Boolean,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ): ExactSessionObservation? {
        val plan = planFor(ticket)
        val started = when (verified) {
            is RemoteAddAndStart.Started -> {
                if (!ticket.intent.startWhenAdded ||
                    !verified.hash.equals(plan.expectedInfoHash, ignoreCase = true)) return null
                true
            }
            is RemoteAddAndStart.AddedStopped -> {
                val stopped = verified.result as? RemoteAddOutcome.VerifiedStopped ?: return null
                if (ticket.intent.startWhenAdded ||
                    !stopped.hash.equals(plan.expectedInfoHash, ignoreCase = true)) return null
                if (plan.useServerDefault && plan.destination.isEmpty()) {
                    val fresh = remote.reconcileStoppedAdd(plan)
                        as? RemoteAddOutcome.VerifiedStopped ?: return null
                    if (fresh != stopped) return null
                } else if (stopped.destination != plan.destination) {
                    // A multi-file torrent is stored under the reviewed
                    // parent PLUS the exact torrent-name root. The engine
                    // already verifies this shape before returning
                    // VerifiedStopped; a fresh read-only verification prevents
                    // a fabricated or stale child root from settling Add.
                    val prefix = plan.destination + "/"
                    val child = stopped.destination.takeIf { it.startsWith(prefix) }
                        ?.removePrefix(prefix) ?: return null
                    if (child.isBlank() || child == "." || child == ".." ||
                        child.length > 2048 || child.any { it == '/' || it == '\\' ||
                            it.code < 32 || it.code == 127 }) return null
                    val fresh = remote.reconcileStoppedAdd(plan)
                        as? RemoteAddOutcome.VerifiedStopped ?: return null
                    if (!fresh.hash.equals(plan.expectedInfoHash, ignoreCase = true) ||
                        fresh.destination != stopped.destination) return null
                }
                false
            }
            is RemoteAddAndStart.StartNotVerified -> return null
        }
        return ExactSessionObservation(
            environment = ticket.intent.target.environment,
            infoHash = plan.expectedInfoHash,
            observedAtEpochMillis = observedAtEpochMillis,
            registered = true,
            reviewedPlaceVerified = true,
            started = started,
            newRegistrationVerified = newRegistrationVerified
        )
    }

    /**
     * A weaker, independently guarded exact-job witness when requested Start
     * cannot be verified after a lost acknowledgement. Registration + Place
     * are proved by stable d.hash and strict d.directory/d.is_multi_file;
     * the Start bit remains unknown, and the write's author remains unknown.
     * Only the user's explicit shared-model acknowledgement can release this
     * ticket. Never construct this witness from progress.registered alone.
     */
    suspend fun registeredJobWithUnverifiedStart(
        ticket: UniversalAddCoordinator.Ticket,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ): ExactSessionObservation? {
        if (!ticket.intent.startWhenAdded) return null
        val plan = planFor(ticket)
        if (!remote.readRegisteredAddAtReviewedPlace(plan)) return null
        return ExactSessionObservation(
            environment = ticket.intent.target.environment,
            infoHash = plan.expectedInfoHash,
            observedAtEpochMillis = observedAtEpochMillis,
            registered = true,
            reviewedPlaceVerified = true,
            started = null,
            newRegistrationVerified = false
        )
    }

    /**
     * An optional exact-job contradiction for the existing Recheck action.
     * Only an authenticated, frozen account/route/hash gets a witness, and
     * a caller-supplied read-start timestamp preserves read acquisition order.
     * Null means no proof, never an invitation to retry a lost load command.
     *
     * Deliberately does not update the coordinator: navigation/shared-model
     * owners decide whether a newer observation may revoke an acknowledgment.
     */
    suspend fun contradictoryExactSession(
        ticket: UniversalAddCoordinator.Ticket,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ): ExactSessionObservation? {
        val plan = planFor(ticket)
        val contradiction = remote.readExactAddContradiction(plan) ?: return null
        return ExactSessionObservation(
            environment = ticket.intent.target.environment,
            infoHash = plan.expectedInfoHash,
            observedAtEpochMillis = observedAtEpochMillis,
            registered = contradiction == RemoteAddContradiction.WRONG_PLACE,
            reviewedPlaceVerified = false,
            started = null,
            newRegistrationVerified = false
        )
    }

    suspend fun progress(ticket: UniversalAddCoordinator.Ticket): RemoteAddProgress =
        remote.readAddProgress(planFor(ticket))
}
