package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gamesbyian.chunks.rpc.RemoteAddProgress
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview

/**
 * One exact account's process-only Add review and recovery, owned above its
 * screen. Never save to Bundle/preferences: sources/metainfo can hold passkeys.
 * No client, password, coroutine scope or mutation callbacks are retained.
 */
class RemoteAddSession(val accountScope: String?, initialDestination: String = "") {
    var destination by mutableStateOf(initialDestination)
    internal var selectedTorrent by mutableStateOf<TorrentMetainfoPreview?>(null)
    internal var selectedBytes by mutableStateOf<ByteArray?>(null)
    internal var textAtFileSelection: String? = null
    internal var useFile by mutableStateOf(false)
    internal var fromPhone by mutableStateOf(false)
    internal val finishInOrder = mutableStateOf(false)
    internal var finishInOrderInitialized = false
    internal var finishInOrderHash: String? = null
    internal var submitting by mutableStateOf(false)
    internal var alreadyAttempted by mutableStateOf(false)
    internal var attemptSource by mutableStateOf<String?>(null)
    internal var nativeStartedAttempt by mutableStateOf(false)
    internal var reviewedAttempt by mutableStateOf<StoppedRemoteAddPlan?>(null)
    internal var submittedPlan by mutableStateOf<StoppedRemoteAddPlan?>(null)
    internal var outcome by mutableStateOf<String?>(null)
    internal var uncertain by mutableStateOf(false)
    internal var verifiedHash by mutableStateOf<String?>(null)
    internal var reportedVerifiedHash: String? = null
    // A completion from an interrupted old draft must not change a newer
    // phone/server draft's shared metadata choice.
    internal var resetArtworkAfterVerification = true
    internal var observedProgress by mutableStateOf<RemoteAddProgress?>(null)

    /** An explicit magnet handoff selects text without reviving an attempted Add. */
    internal fun preferMagnet() {
        if (!alreadyAttempted) useFile = false
    }

    /** Only callers which know no engine Add was sent may release this latch. */
    internal fun refusedBeforeAdd() {
        alreadyAttempted = false
        attemptSource = null
        reviewedAttempt = null
        submittedPlan = null
        nativeStartedAttempt = false
        uncertain = false
    }

    /** Only an observed successful Add opens another draft, never a timeout. */
    internal fun beginNextDraft(): Boolean {
        if (submitting || verifiedHash == null || uncertain) return false
        selectedTorrent = null
        selectedBytes = null
        textAtFileSelection = null
        useFile = false
        fromPhone = false
        finishInOrderInitialized = false
        alreadyAttempted = false
        attemptSource = null
        nativeStartedAttempt = false
        reviewedAttempt = null
        submittedPlan = null
        outcome = null
        uncertain = false
        verifiedHash = null
        reportedVerifiedHash = null
        resetArtworkAfterVerification = true
        observedProgress = null
        destination = ""
        return true
    }
}

/** Exact-account reviews plus one explicit source selection awaiting authentication. */
internal class RemoteAddSessions {
    private val accounts = mutableMapOf<String, RemoteAddSession>()
    private var pendingMagnetSelection = false

    fun forAccount(scope: String): RemoteAddSession {
        require(scope.isNotBlank()) { "An authenticated account is required for Add" }
        val session = accounts.getOrPut(scope) { RemoteAddSession(scope) }
        if (pendingMagnetSelection) {
            session.preferMagnet()
            pendingMagnetSelection = false
        }
        return session
    }

    fun selectMagnet(authenticatedScope: String) {
        pendingMagnetSelection = true
        if (authenticatedScope.isNotBlank()) forAccount(authenticatedScope)
    }

    fun clearPendingSourceSelection() { pendingMagnetSelection = false }
}
