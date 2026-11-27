package io.github.gamesbyian.chunks

/**
 * Whether combined Transfers may offer Start/Stop for one row (A2611-02: Details and transfer
 * operations). Pure: the screen gathers each observed fact; nothing here reads state or writes.
 * Offering is never the write itself: the shared operation workflow re-checks before dispatch.
 */
internal object CombinedLifecycleEligibility {
    data class Facts(
        /** The row's details were read for this exact job in the current frame. */
        val readCurrent: Boolean,
        /** The row's environment can claim a complete live inventory right now. */
        val completeLiveInventory: Boolean,
        val isLocal: Boolean,
        val localPrivacyMayShow: Boolean,
        val localRunning: Boolean,
        /** The row belongs to the one authenticated rTorrent server shown on this screen. */
        val isAuthenticatedServer: Boolean,
        val serverLifecyclePresent: Boolean,
        /** [verifiedCombinedLifecycleOwner] returned the session for that exact server. */
        val verifiedServerOwner: Boolean,
        val serverPrivacyMayShow: Boolean,
        /** null = unknown; only an explicit false (fresh) allows offering. */
        val serverStale: Boolean?,
        val infoHash: String
    )

    fun eligible(f: Facts): Boolean = f.readCurrent && f.completeLiveInventory &&
        ((f.isLocal && f.localPrivacyMayShow && f.localRunning) ||
            (f.isAuthenticatedServer && f.serverLifecyclePresent && f.verifiedServerOwner &&
                f.serverPrivacyMayShow && f.serverStale == false &&
                // rTorrent Start/Stop only supports exact v1 hashes. Keep
                // unsupported remote identities read-only before review.
                f.infoHash.matches(V1_TRANSFER_HASH)))
}
