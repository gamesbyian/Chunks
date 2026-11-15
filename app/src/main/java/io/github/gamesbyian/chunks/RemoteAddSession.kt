package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gamesbyian.chunks.rpc.RemoteAddProgress
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
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
    internal var fromLocal by mutableStateOf(false)
    internal val finishInOrder = mutableStateOf(false)
    internal var finishInOrderInitialized = false
    internal var finishInOrderHash: String? = null
    /** Reviewed "Files to download" skips (native metainfo indexes) for [skippedFilesHash]. */
    internal var skippedFiles by mutableStateOf<Set<Int>>(emptySet())
    internal var skippedFilesHash: String? = null
    internal var fileChoiceOpen by mutableStateOf(false)
    internal var submitting by mutableStateOf(false)
    internal var alreadyAttempted by mutableStateOf(false)
    internal var attemptSource by mutableStateOf<String?>(null)
    // The shared coordinator owns the only submitted remote attempt. The UI
    // fields below describe it; they never grant a second engine write.
    internal var coordinator = UniversalAddCoordinator()
    internal var attemptTicket by mutableStateOf<UniversalAddCoordinator.Ticket?>(null)
    internal var outcome by mutableStateOf<String?>(null)
    internal var uncertain by mutableStateOf(false)
    internal var verifiedHash by mutableStateOf<String?>(null)
    internal var reportedVerifiedHash: String? = null
    /** Frozen optional Chunks list alias, valid only for the retained ticket. */
    internal var frozenAlias: String? = null
    /** The submitted UI label, retained for the exact account's result across renames. */
    internal var submittedDisplayName by mutableStateOf<String?>(null)
    // A completion from an interrupted old draft must not change a newer
    // local/server draft's shared metadata choice.
    internal var resetArtworkAfterVerification = true
    internal var observedProgress by mutableStateOf<RemoteAddProgress?>(null)

    // The user's own Android document behind the reviewed .torrent bytes, if
    // any. Bound to the WHOLE reviewed byte digest, so a later different
    // source can never inherit this document's Archive/Delete authority.
    // Process memory only, like the bytes themselves.
    private var originalDocument: android.net.Uri? = null
    private var originalDocumentSha256: ByteArray? = null
    /** Handling frozen with the Add click; only a verified NEW exact job may act on it. */
    internal var frozenOriginal: FrozenOriginalHandling? = null
    /** The separately approved deletion still awaiting the person's answer. */
    internal var originalDeletion by mutableStateOf<OriginalDeletionRequest?>(null)
    internal var originalNote by mutableStateOf<String?>(null)

    internal class FrozenOriginalHandling(
        val document: android.net.Uri,
        val handling: OriginalTorrentHandling,
        val deletable: Boolean,
        val bytes: ByteArray
    )

    internal fun bindOriginalDocument(document: android.net.Uri?, bytes: ByteArray?) {
        originalDocument = document?.takeIf { bytes != null }
        originalDocumentSha256 = if (originalDocument != null) sha256(requireNotNull(bytes)) else null
    }

    /** The bound document only while [bytes] are byte-for-byte the reviewed ones. */
    internal fun originalDocumentFor(bytes: ByteArray?): android.net.Uri? {
        val document = originalDocument ?: return null
        val digest = originalDocumentSha256 ?: return null
        if (bytes == null || !java.security.MessageDigest.isEqual(digest, sha256(bytes))) return null
        return document
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    /** An explicit magnet handoff selects text without reviving an attempted Add. */
    internal fun preferMagnet() {
        if (!alreadyAttempted) useFile = false
    }

    /** Only callers which know no engine Add was sent may release this latch. */
    internal fun refusedBeforeAdd() {
        alreadyAttempted = false
        attemptSource = null
        frozenAlias = null
        attemptTicket?.let(coordinator::refusedBeforeAnyWrite)
        attemptTicket = null
        uncertain = false
        frozenOriginal = null
    }

    /**
     * A new draft is allowed only after a proved Add or a *separate user click*
     * acknowledging a positively verified exact session after an uncertain
     * acknowledgement. The latter deliberately does not attribute the Add to
     * Chunks; neither path resends the original command.
     */
    internal fun beginNextDraft(): Boolean {
        val registeredWithoutStart =
            coordinator.phase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED
        if (submitting || (verifiedHash == null && !registeredWithoutStart)) return false
        when (coordinator.phase) {
            UniversalAddCoordinator.Phase.VERIFIED -> {
                if (uncertain || !coordinator.finishVerified()) return false
            }
            UniversalAddCoordinator.Phase.SESSION_CONFIRMED -> {
                // An uncertain provenance bit is expected here; the exact
                // hash/Place/Start proof is separately held by the coordinator.
                if (!coordinator.acknowledgeExactSession()) return false
            }
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED -> {
                // The exact hash and Place were observed, but payload Start
                // may remain unverified indefinitely while peers are absent.
                // This releases no write without the person's explicit click.
                if (!coordinator.acknowledgeObservedJob()) return false
            }
            else -> return false
        }
        selectedTorrent = null
        selectedBytes = null
        textAtFileSelection = null
        useFile = false
        fromLocal = false
        finishInOrderInitialized = false
        alreadyAttempted = false
        attemptSource = null
        attemptTicket = null
        outcome = null
        uncertain = false
        verifiedHash = null
        reportedVerifiedHash = null
        frozenAlias = null
        submittedDisplayName = null
        resetArtworkAfterVerification = true
        observedProgress = null
        bindOriginalDocument(null, null)
        frozenOriginal = null
        originalDeletion = null
        originalNote = null
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
