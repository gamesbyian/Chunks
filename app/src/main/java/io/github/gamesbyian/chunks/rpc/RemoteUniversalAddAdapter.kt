package io.github.gamesbyian.chunks.rpc

/**
 * One immutable, process-only rTorrent review handed to the shared Universal Add
 * coordinator. It retains the exact authenticated endpoint + principal, source
 * identity, server Place and Start intent; it never persists passkey-bearing
 * metainfo/magnets, credentials or submission callbacks.
 *
 * Only the shared coordinator may authorize an attempt and latch an uncertain
 * write. This value grants no authority to submit or retry by itself.
 */
internal class RemoteReviewedAdd private constructor(
    val accountScope: String,
    val plan: StoppedRemoteAddPlan,
    private val frozenTorrentBytes: ByteArray?,
    val startAfterAdd: Boolean,
    val prioritiesBeforeStart: Boolean
) {
    companion object {
        fun magnet(
            accountScope: String, plan: StoppedRemoteAddPlan, startAfterAdd: Boolean
        ): RemoteReviewedAdd {
            require(accountScope.isNotBlank()) { "Select an exact authenticated server" }
            return RemoteReviewedAdd(accountScope, plan, null, startAfterAdd, false)
        }

        fun torrent(
            accountScope: String,
            bytes: ByteArray,
            destination: String,
            useServerDefault: Boolean,
            startAfterAdd: Boolean,
            prioritiesBeforeStart: Boolean = false
        ): RemoteReviewedAdd {
            require(accountScope.isNotBlank()) { "Select an exact authenticated server" }
            val frozen = bytes.copyOf()
            val preview = TorrentMetainfoReader.preview(frozen)
            require(!prioritiesBeforeStart || (startAfterAdd && preview.fileCount >= 2)) {
                "File priorities require a reviewed multi-file Add and Start"
            }
            val identity = "magnet:?xt=urn:btih:" + preview.infoHashV1
            val plan = if (useServerDefault)
                StoppedRemoteAddPlan.fromMagnetAtServerDefault(identity, destination)
            else StoppedRemoteAddPlan.fromMagnet(identity, destination)
            return RemoteReviewedAdd(accountScope, plan, frozen, startAfterAdd, prioritiesBeforeStart)
        }
    }

    /** A caller cannot mutate the bytes frozen for an earlier approval. */
    internal fun torrentBytesForSubmission(): ByteArray? = frozenTorrentBytes?.copyOf()

    override fun toString(): String = "RemoteReviewedAdd(<redacted>)"
}

/** Existing engine result plus the optional user-requested file priority explanation. */
internal class RemoteAddExecution(
    val result: RemoteAddAndStart,
    val orderNote: String?
)

/**
 * Thin rTorrent submission/recovery bridge for one shared Add attempt owner.
 *
 * Reuses RTorrentRemote's authenticated load, exact-hash duplicate checks,
 * readback and native load.start/load.raw_start; no new transport, executor,
 * persistence, retry loop or alternative Add authority. The coordinator must
 * call submit at most once after explicit approval and retain its attempted
 * latch until read-only reconciliation confirms the original desired state.
 */
internal class RemoteUniversalAddAdapter(private val remote: RTorrentRemote) {
    private fun requireExactAccount(review: RemoteReviewedAdd) {
        check(review.accountScope == remote.addAccountScope()) {
            "The authenticated rTorrent account or route changed; reconnect and review"
        }
    }

    suspend fun submit(
        review: RemoteReviewedAdd,
        /** Existing Finish in Order implementation; runs only after verified stopped Add. */
        onVerifiedStoppedBeforeStart: (suspend (String) -> String?)? = null
    ): RemoteAddExecution {
        requireExactAccount(review)
        require(!review.prioritiesBeforeStart || onVerifiedStoppedBeforeStart != null) {
            "A reviewed priority-before-Start request requires its existing priority adapter"
        }
        val bytes = review.torrentBytesForSubmission()
        val plan = review.plan
        // ruTorrent's standard Start option is native start-on-load. Retain
        // stopped-first only for actual reviewed priority-before-Start work.
        if (review.startAfterAdd && !review.prioritiesBeforeStart) {
            val started = if (bytes == null) remote.loadStarted(plan)
                else remote.loadStartedTorrentBytes(bytes, plan.destination, plan.useServerDefault)
            return RemoteAddExecution(started, null)
        }
        var note: String? = null
        val result = RemoteAddStarter.run(
            startAfterVerifiedAdd = review.startAfterAdd,
            add = {
                val added = if (bytes == null) remote.loadStopped(plan)
                    else remote.loadStoppedTorrentBytes(bytes, plan.destination, plan.useServerDefault)
                if (review.prioritiesBeforeStart && added is RemoteAddOutcome.VerifiedStopped)
                    note = requireNotNull(onVerifiedStoppedBeforeStart).invoke(added.hash)
                added
            },
            start = { hash -> remote.setStarted(hash, true) }
        )
        return RemoteAddExecution(result, note)
    }

    /**
     * No mutation is possible here. A two-stage stopped/priority/Start Add
     * recovers using the ORIGINAL Start intent, even if its second command
     * timed out; a stopped-only read would permanently misclassify it.
     */
    suspend fun reconcile(review: RemoteReviewedAdd): RemoteAddAndStart {
        requireExactAccount(review)
        return if (review.startAfterAdd) remote.reconcileStartedAdd(review.plan)
        else RemoteAddAndStart.AddedStopped(remote.reconcileStoppedAdd(review.plan))
    }

    suspend fun progress(review: RemoteReviewedAdd): RemoteAddProgress {
        requireExactAccount(review)
        return remote.readAddProgress(review.plan)
    }
}
