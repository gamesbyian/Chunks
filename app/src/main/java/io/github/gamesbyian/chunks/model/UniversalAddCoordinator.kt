package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * Process-only source selected for a reviewed Add. A URL/document has already
 * passed the existing source-intake and metainfo verification before becoming
 * torrent bytes here. Never serialize this object, its origin, or a raw magnet:
 * private tracker tokens may be present in any of them.
 */
internal class UniversalAddSource private constructor(
    val kind: Kind,
    val expectedInfoHash: String,
    private val magnet: String?,
    private val metainfo: ByteArray?,
    private val originalReference: String?
) {
    enum class Kind { MAGNET, TORRENT_METAINFO }

    companion object {
        fun magnet(validatedMagnet: String, expectedInfoHash: String): UniversalAddSource {
            require(validatedMagnet.startsWith("magnet:?", ignoreCase = true))
            val identity = normalHash(expectedInfoHash)
            // The existing MagnetDraftCheck must normalize base32/encoded xt
            // first. Still bind this exact frozen source to the reviewed hash;
            // a mislabeled source could otherwise reconcile the wrong job.
            val btih = validatedMagnet.substringAfter("?").split('&')
                .mapNotNull { part ->
                    val parameter = part.split('=', limit = 2)
                    if (parameter.size == 2 && parameter[0].equals("xt", ignoreCase = true) &&
                        parameter[1].startsWith("urn:btih:", ignoreCase = true))
                        parameter[1].substring("urn:btih:".length).lowercase(Locale.ROOT)
                    else null
                }
            require(btih.size == 1 && btih.single() == identity) {
                "The reviewed magnet and its expected hash disagree"
            }
            return UniversalAddSource(Kind.MAGNET, identity, validatedMagnet, null, null)
        }

        fun metainfo(verifiedBytes: ByteArray, expectedInfoHash: String,
                     originalReference: String? = null): UniversalAddSource {
            require(verifiedBytes.isNotEmpty())
            return UniversalAddSource(Kind.TORRENT_METAINFO, normalHash(expectedInfoHash),
                null, verifiedBytes.copyOf(), originalReference)
        }

        private fun normalHash(hash: String): String {
            val canonical = hash.lowercase(Locale.ROOT)
            require(canonical.matches(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})"))) {
                "Expected infohash must be a valid v1/v2 hexadecimal identity"
            }
            return canonical
        }
    }

    /** Source bytes/text are exposed only for the already-selected engine adapter. */
    fun magnetForSubmission(): String? = magnet
    fun metainfoForSubmission(): ByteArray? = metainfo?.copyOf()
    fun originalReferenceForReview(): String? = originalReference
    override fun toString(): String = "UniversalAddSource(kind=$kind, private)"
}

/** Exact verified target. Saved/visible/last-browsed environments are not authority. */
internal sealed class UniversalAddTarget {
    abstract val environment: EnvironmentId

    object Local : UniversalAddTarget() {
        override val environment: EnvironmentId = EnvironmentSnapshots.LOCAL
        override fun toString(): String = "Local"
    }

    class AuthenticatedServer(val accountScope: String) : UniversalAddTarget() {
        override val environment: EnvironmentId =
            requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(accountScope)) {
                "Remote Add requires the exact authenticated endpoint and principal"
            }

        override fun toString(): String = "AuthenticatedServer(private)"
    }
}

/**
 * Place is a reviewed selection, not a substitute for the engine's writable
 * destination/preflight check. Do not normalize an opaque local Place ID into
 * an rTorrent path or infer a default from a previous account.
 */
internal sealed class UniversalAddPlace {
    class Local(val verifiedPlaceId: String, val relativeSubfolder: String = "") : UniversalAddPlace() {
        init { require(verifiedPlaceId.isNotBlank()) }
        override fun toString(): String = "Local(private)"
    }

    /** Empty means the user selected the native default, whose path requires post-Add observation. */
    class ServerDefault(val observedDefaultPath: String = "") : UniversalAddPlace() {
        override fun toString(): String = "ServerDefault(private)"
    }

    class ServerCustom(val absolutePath: String) : UniversalAddPlace() {
        init { require(absolutePath.startsWith("/") && absolutePath.isNotBlank()) }
        override fun toString(): String = "ServerCustom(private)"
    }
}

/** One immutable user-reviewed intent, not an executor or persisted job record. */
internal class UniversalAddIntent(
    val source: UniversalAddSource,
    val target: UniversalAddTarget,
    val place: UniversalAddPlace,
    val startWhenAdded: Boolean,
    val allowArtwork: Boolean
) {
    override fun toString(): String =
        "UniversalAddIntent(source=${source.kind}, target=${target.javaClass.simpleName}, private)"
}

/**
 * Shared, single-attempt authorization/fencing model. The existing local and
 * rTorrent executors own actual writes, adapter-specific preflight and
 * proof of registration/Place/Start. Neither a capability probe nor a matching
 * inventory row grants write authorization or retries an ambiguous command.
 *
 * Keep the same instance above navigation while the Activity process lives.
 * Do not restore private sources or an attempted command from SavedState.
 */
internal class UniversalAddCoordinator {
    enum class Phase {
        EMPTY, REVIEWED, AUTHORIZED, WRITE_ATTEMPTED, READBACK_REQUIRED,
        OBSERVED_UNCERTAIN, JOB_CONFIRMED_START_UNVERIFIED, SESSION_CONFIRMED, VERIFIED
    }

    data class ReviewDecision(val accepted: Boolean, val reason: String)

    /**
     * Per-hash readback from an existing engine adapter, including jobs hidden
     * from rTorrent's main view. The adapter must actually verify each flag
     * against the selected session, never deduce it from a guessed catalog row.
     * "New registration" needs positive provenance from that submission;
     * a post-timeout lookup can verify a session without proving who added it.
     *
     * The timestamp must be tied to a trustworthy adapter sampling/generation
     * boundary (for RPC reads, capture before the read starts), not assigned
     * merely when a delayed response is translated into this value. A late
     * completion of an older read can otherwise acquire a deceptively newer
     * timestamp. The coordinator compares trusted timestamps, not RPC order.
     */
    data class ExactSessionObservation(
        val environment: EnvironmentId,
        val infoHash: String,
        val observedAtEpochMillis: Long,
        val registered: Boolean,
        val reviewedPlaceVerified: Boolean,
        val started: Boolean?,
        val newRegistrationVerified: Boolean
    )

    enum class Readback {
        WRONG_TICKET, WRONG_ENVIRONMENT, NEEDS_FRESH_INVENTORY,
        NOT_OBSERVED, OBSERVED_UNVERIFIED, EXACT_SESSION_CONFIRMED, VERIFIED
    }

    /** An opaque reference to one frozen, authorized write. Never log it with private input. */
    class Ticket internal constructor(
        val intent: UniversalAddIntent,
        val submittedAtEpochMillis: Long
    ) {
        override fun toString(): String = "UniversalAddTicket(private)"
    }

    var phase: Phase = Phase.EMPTY
        private set
    private var reviewed: UniversalAddIntent? = null
    private var currentTicket: Ticket? = null
    // Compare adapter-provided *sampling-order* timestamps, not callback
    // completion order. Late results from older samples cannot rewrite newer
    // accepted evidence when the adapter stamps them before asynchronous reads
    // or uses an independently fenced native sampling generation. This alone
    // cannot detect an older RPC mislabeled with a later completion timestamp.
    // Share one per-ticket watermark across both readback APIs, without IO.
    private var latestAcceptedObservationAtEpochMillis: Long? = null

    private fun isStaleReadback(ticket: Ticket, observedAt: Long): Boolean =
        observedAt < 0 || observedAt < ticket.submittedAtEpochMillis ||
            (latestAcceptedObservationAtEpochMillis?.let { observedAt < it } == true)

    private fun acceptReadbackAt(observedAt: Long) {
        latestAcceptedObservationAtEpochMillis = maxOf(
            latestAcceptedObservationAtEpochMillis ?: observedAt, observedAt)
    }

    /** Read-only process-memory continuity across Add/Transfers/Connections navigation. */
    val retainedReview: UniversalAddIntent? get() = reviewed
    /** An attempted ticket is retained for read-only recovery, never replayed. */
    val retainedTicket: Ticket? get() = currentTicket

    fun review(
        intent: UniversalAddIntent,
        capabilities: EnvironmentCapabilitySnapshot,
        availability: EnvironmentAvailability
    ): ReviewDecision {
        if (phase == Phase.WRITE_ATTEMPTED || phase == Phase.READBACK_REQUIRED ||
            phase == Phase.OBSERVED_UNCERTAIN || phase == Phase.JOB_CONFIRMED_START_UNVERIFIED ||
            phase == Phase.SESSION_CONFIRMED || phase == Phase.VERIFIED)
            return ReviewDecision(false, "Resolve or finish the previous exact Add first")
        if (capabilities.environment != intent.target.environment)
            return ReviewDecision(false, "Capabilities belong to another environment")
        if ((intent.target is UniversalAddTarget.Local) != (intent.place is UniversalAddPlace.Local))
            return ReviewDecision(false, "Place belongs to a different engine")
        val operation = if (intent.source.kind == UniversalAddSource.Kind.MAGNET)
            EnvironmentOperation.ADD_MAGNET else EnvironmentOperation.ADD_TORRENT_FILE
        val add = capabilities.decide(operation, availability)
        if (!add.allowed) return ReviewDecision(false, add.explanation)
        if (intent.place is UniversalAddPlace.ServerDefault) {
            val default = capabilities.decide(EnvironmentOperation.SERVER_DEFAULT_ADD_FOLDER, availability)
            if (!default.allowed) return ReviewDecision(false, default.explanation)
        }
        reviewed = intent
        currentTicket = null
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.REVIEWED // Any revised source/Place/Start invalidates previous approval.
        return ReviewDecision(true, add.explanation)
    }

    /** Call only from the user's explicit confirmation, not during composition or recovery. */
    fun authorizeByUser(): Boolean {
        if (phase != Phase.REVIEWED) return false
        phase = Phase.AUTHORIZED
        return true
    }

    /**
     * Must be called synchronously before entering the engine's suspend/write
     * boundary. One confirmation yields at most one ticket.
     */
    fun beginSubmission(nowEpochMillis: Long): Ticket? {
        if (phase != Phase.AUTHORIZED || nowEpochMillis < 0) return null
        val ticket = Ticket(requireNotNull(reviewed), nowEpochMillis)
        currentTicket = ticket
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.WRITE_ATTEMPTED
        return ticket
    }

    /** Only a positively established no-write preflight refusal may disarm the latch. */
    fun refusedBeforeAnyWrite(ticket: Ticket): Boolean {
        if (currentTicket !== ticket || phase != Phase.WRITE_ATTEMPTED) return false
        currentTicket = null
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.REVIEWED
        return true
    }

    /** Timeouts, exceptions and lost ACKs always retain the attempted-write fence. */
    fun requireReadback(ticket: Ticket): Boolean {
        if (currentTicket !== ticket ||
            phase !in setOf(Phase.WRITE_ATTEMPTED, Phase.READBACK_REQUIRED, Phase.OBSERVED_UNCERTAIN,
                Phase.JOB_CONFIRMED_START_UNVERIFIED))
            return false
        // A later ambiguous request cannot erase an already positively
        // observed exact hash and Place, or the user's acknowledgment option.
        if (phase != Phase.JOB_CONFIRMED_START_UNVERIFIED) phase = Phase.READBACK_REQUIRED
        return true
    }

    /**
     * Read-only exact-environment/hash reconciliation. Presence alone cannot
     * prove a NEW Add; the existing adapter must separately confirm a new
     * registration, the reviewed Place and (when requested) actual Start.
     * A no-match read never permits a blind second write.
     *
     * Snapshot observedAtEpochMillis must represent the adapter-accepted
     * sample/generation, not an unfenced asynchronous callback completion.
     */
    fun reconcile(
        ticket: Ticket,
        snapshot: EnvironmentTransferSnapshot,
        adapterConfirmedNewAdd: Boolean = false,
        adapterVerifiedPlace: Boolean = false,
        adapterVerifiedStart: Boolean = false
    ): Readback {
        if (currentTicket !== ticket || phase !in setOf(
                Phase.WRITE_ATTEMPTED, Phase.READBACK_REQUIRED, Phase.OBSERVED_UNCERTAIN,
                Phase.JOB_CONFIRMED_START_UNVERIFIED, Phase.SESSION_CONFIRMED, Phase.VERIFIED))
            return Readback.WRONG_TICKET
        val intent = ticket.intent
        if (snapshot.environment != intent.target.environment)
            return Readback.WRONG_ENVIRONMENT
        if (phase == Phase.VERIFIED) return Readback.VERIFIED
        if (phase == Phase.SESSION_CONFIRMED) return Readback.EXACT_SESSION_CONFIRMED
        val observedAt = snapshot.observedAtEpochMillis
        if (!snapshot.canClaimCompleteLiveInventory || observedAt == null ||
            isStaleReadback(ticket, observedAt))
            return Readback.NEEDS_FRESH_INVENTORY
        // A complete inventory is evidence even when the hash is absent.
        // A late older inventory must never overwrite newer exact-session proof.
        acceptReadbackAt(observedAt)
        val match = snapshot.transfers.singleOrNull {
            it.key.infoHash == intent.source.expectedInfoHash &&
                it.key.environment == intent.target.environment
        } ?: run {
            // A complete *newer* exact-environment catalog disproves the
            // previously observed job. Do not leave its old acknowledgment
            // CTA enabled. Incomplete/stale catalogs are filtered above.
            phase = Phase.READBACK_REQUIRED
            return Readback.NOT_OBSERVED
        }
        val startAgrees = if (intent.startWhenAdded)
            adapterVerifiedStart && match.started == true else match.started == false
        if (!adapterConfirmedNewAdd || !adapterVerifiedPlace || !startAgrees) {
            if (adapterVerifiedPlace && !startAgrees) {
                // A fresh full-inventory hash plus independently verified Place
                // proves that a job exists, but does not prove requested Start.
                // Only an explicit user acknowledgment may retire this ticket.
                phase = Phase.JOB_CONFIRMED_START_UNVERIFIED
            } else {
                // A newer read that disproves reviewed Place (or no longer
                // proves the original incomplete Start premise) revokes the
                // earlier positive job/Place acknowledgment eligibility.
                phase = Phase.OBSERVED_UNCERTAIN
            }
            return Readback.OBSERVED_UNVERIFIED
        }
        phase = Phase.VERIFIED
        return Readback.VERIFIED
    }

    /**
     * The same read-only fence for an exact RPC/session getter, where the job
     * can exist outside rTorrent's main list. This never issues a command.
     * A session first observed after a lost ACK is **not** evidence that this
     * Add registered it. It can be acknowledged separately for safe navigation
     * without manufacturing a successful NEW Add attribution.
     *
     * A trustworthy per-read sampling timestamp is required, not an arbitrary
     * wall-clock instant assigned after a stale asynchronous RPC returns.
     */
    fun reconcileExactSession(ticket: Ticket, observation: ExactSessionObservation): Readback {
        if (currentTicket !== ticket || phase !in setOf(
                Phase.WRITE_ATTEMPTED, Phase.READBACK_REQUIRED, Phase.OBSERVED_UNCERTAIN,
                Phase.JOB_CONFIRMED_START_UNVERIFIED, Phase.SESSION_CONFIRMED, Phase.VERIFIED))
            return Readback.WRONG_TICKET
        if (observation.environment != ticket.intent.target.environment)
            return Readback.WRONG_ENVIRONMENT
        if (phase == Phase.VERIFIED) return Readback.VERIFIED
        if (phase == Phase.SESSION_CONFIRMED) {
            // Exact-session confirmation is eligible for explicit user
            // acknowledgment, but a newer *exact* observation may contradict
            // it before that acknowledgment. Unlike a general rTorrent main
            // catalog, this getter can authoritatively observe hidden jobs.
            // Wrong principal (above), hash and stale reads cannot revoke it.
            if (isStaleReadback(ticket, observation.observedAtEpochMillis))
                return Readback.NEEDS_FRESH_INVENTORY
            if (!observation.infoHash.equals(ticket.intent.source.expectedInfoHash, ignoreCase = true))
                return Readback.NOT_OBSERVED
            acceptReadbackAt(observation.observedAtEpochMillis)
            if (!observation.registered) {
                phase = Phase.READBACK_REQUIRED
                return Readback.NOT_OBSERVED
            }
            if (!observation.reviewedPlaceVerified) {
                phase = Phase.OBSERVED_UNCERTAIN
                return Readback.OBSERVED_UNVERIFIED
            }
            // A new concrete Start mismatch revokes the *full-intent*
            // confirmation. An unknown Start bit does not contradict the
            // previous positive exact-job/Place/Start observation.
            if (observation.started != null &&
                observation.started != ticket.intent.startWhenAdded) {
                phase = Phase.JOB_CONFIRMED_START_UNVERIFIED
                return Readback.OBSERVED_UNVERIFIED
            }
            return Readback.EXACT_SESSION_CONFIRMED
        }
        if (isStaleReadback(ticket, observation.observedAtEpochMillis))
            return Readback.NEEDS_FRESH_INVENTORY
        val expected = ticket.intent.source.expectedInfoHash
        if (!observation.infoHash.equals(expected, ignoreCase = true))
            return Readback.NOT_OBSERVED
        // A valid exact-hash observation includes an authoritative negative
        // when the job is not registered. Do not accept unrelated hash probes.
        acceptReadbackAt(observation.observedAtEpochMillis)
        if (!observation.registered) {
            // The exact hash was queried in the intended account, after
            // the last accepted sample. A genuine negative supersedes a
            // previously seen registration; only fresh positive proof may
            // restore its acknowledgement CTA.
            phase = Phase.READBACK_REQUIRED
            return Readback.NOT_OBSERVED
        }
        val startMatches = if (ticket.intent.startWhenAdded)
            observation.started == true else observation.started == false
        if (!observation.reviewedPlaceVerified || !startMatches) {
            if (observation.reviewedPlaceVerified && !startMatches) {
                // This includes a local magnet registered in metadata-only
                // mode while its deferred payload Start cannot yet be proved.
                // The existing job may be acknowledged, never auto-started.
                phase = Phase.JOB_CONFIRMED_START_UNVERIFIED
            } else {
                // A newer registered hash with a disproved Place is not a
                // currently confirmed job at the reviewed destination.
                phase = Phase.OBSERVED_UNCERTAIN
            }
            return Readback.OBSERVED_UNVERIFIED
        }
        if (!observation.newRegistrationVerified) {
            phase = Phase.SESSION_CONFIRMED
            return Readback.EXACT_SESSION_CONFIRMED
        }
        phase = Phase.VERIFIED
        return Readback.VERIFIED
    }

    /**
     * Release an exact observed job with an unverified requested Start only
     * after the person explicitly acknowledges the incomplete result.
     *
     * A registered magnet may wait indefinitely for metadata/peers before its
     * deferred payload Start can be checked. Do not block all future Adds,
     * silently claim Start, or repeat the non-idempotent native/RPC load.
     * Wrong hash/account or stale/incomplete reads cannot grant this phase.
     * A newer authoritative negative hash/registration or contradicted Place
     * revokes earlier eligibility; a later positive exact read may restore it.
     * Fresh Start proof may instead upgrade to VERIFIED or SESSION_CONFIRMED.
     */
    fun acknowledgeObservedJob(): Boolean {
        if (phase != Phase.JOB_CONFIRMED_START_UNVERIFIED) return false
        reviewed = null
        currentTicket = null
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.EMPTY
        return true
    }

    /**
     * The person can deliberately end recovery after exact-session proof.
     * This does not say the previous Add succeeded, and cannot replay it.
     * The existing adapter's duplicate check still applies to a future Add.
     */
    fun acknowledgeExactSession(): Boolean {
        if (phase != Phase.SESSION_CONFIRMED) return false
        reviewed = null
        currentTicket = null
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.EMPTY
        return true
    }

    /** A positively verified result needs an explicit next-draft action. */
    fun finishVerified(): Boolean {
        if (phase != Phase.VERIFIED) return false
        reviewed = null
        currentTicket = null
        latestAcceptedObservationAtEpochMillis = null
        phase = Phase.EMPTY
        return true
    }
}
