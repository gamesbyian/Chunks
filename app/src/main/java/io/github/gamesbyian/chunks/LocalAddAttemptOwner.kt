package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gamesbyian.chunks.create.SharedCreateDocument

internal sealed interface ExternalIntakeDelivery {
    class Add(val source: ExternalAddSource) : ExternalIntakeDelivery
    class Create(val request: SharedCreateDocument.Request) : ExternalIntakeDelivery
}

/** One retained source event. Its bytes and original-document authority travel together. */
internal class PreparedRemoteTorrent(bytes: ByteArray, val reviewed: PendingLocalAdd?) {
    val bytes = bytes.copyOf()
    val originalDocument get() = reviewed?.originalDocument
}

internal class LocalAddAttemptOwner : ViewModel() {
    val session = LocalAddAttemptSession()
    // Retain private Add drafts through configuration changes in RAM only.
    // Never serialize magnet passkeys, document bytes or account scope.
    val sourceDraft = mutableStateOf("")
    // The optional Chunks-only label belongs to the single in-RAM Add draft.
    val aliasDraft = mutableStateOf("")
    val transferSearch = UniversalAddNavigationSearch()
    val reviewedTorrent = mutableStateOf<PendingLocalAdd?>(null)
    val pendingSharedMagnet = mutableStateOf<String?>(null)
    val pendingSharedDocument = mutableStateOf<Uri?>(null)
    val readingSharedDocument = mutableStateOf<Uri?>(null)
    val artworkChoices = AddArtworkDraftChoices()
    val addDestination = mutableStateOf<UniversalAddRouteTarget>(UniversalAddRouteTarget.Local)
    val routingAutoAppliedFor = mutableStateOf<String?>(null)
    val routingPersonChoiceFor = mutableStateOf<String?>(null)
    val artworkExpanded = mutableStateOf(false)
    val sharedTorrentError = mutableStateOf<String?>(null)
    var serverHandoff by mutableStateOf<PreparedRemoteTorrent?>(null)
        private set
    val remoteSessions = RemoteAddSessions()
    // One Transmission/qBittorrent/Deluge Add attempt; RAM only, never persisted.
    val protocolAttempt = ProtocolAddAttempt()
    // One process-only delivery slot. Identical shares are still distinct events.
    var incomingDelivery by mutableStateOf<ExternalIntakeDelivery?>(null)
        private set

    fun offerAdd(candidate: ExternalAddSource?) {
        if (candidate == null) return
        // Until a consumer claims this exact event, this slot is its only
        // authority. A second provisional copy in pendingSharedDocument could
        // survive a newer Create delivery and unexpectedly resurface as Add.
        // The retained slot already survives disposal of the earlier reader.
        incomingDelivery = ExternalIntakeDelivery.Add(candidate)
    }

    fun offerCreate(request: SharedCreateDocument.Request) {
        incomingDelivery = ExternalIntakeDelivery.Create(request)
    }

    /** Claim before adopting a captured event. A stale consumer cannot erase a newer share. */
    fun claimIncoming(expected: ExternalIntakeDelivery?): Boolean {
        if (expected == null || incomingDelivery !== expected) return false
        incomingDelivery = null
        return true
    }

    // Create's read-only shared document scan is cancellable on rotation.
    // Retain both the unacknowledged request and an adopted source in RAM,
    // so recreation neither loses a half-read share nor replays a finished one.
    val createReviewRequest = mutableStateOf<ExternalIntakeDelivery.Create?>(null)
    val createSource = mutableStateOf<io.github.gamesbyian.chunks.create.CreateTorrentSource?>(null)
    // The one-shot remote Add and exact readback outlive both the Add route and
    // Activity recreation, but are cancelled when this app-owned ViewModel is
    // finally cleared. No source, account or private tracker enters SavedState.
    // One app-owned Add lifetime spans Local native writes and rTorrent RPC.
    // This never persists an attempted command across process death.
    val addAttemptScope get() = viewModelScope
    // The one app-owned execution/observation owner for every client's Add
    // write and read-only recheck: Local, rTorrent and the protocol clients.
    val attempts = UniversalAddAttemptController { viewModelScope }

    /**
     * Stage exactly one explicitly chosen remote review source. Only the current
     * reviewed Android document may carry original-document handling authority;
     * a transfer export or archive entry has none, even for byte-identical metainfo.
     * Refuse a stale reviewed-source callback before changing any draft.
     */
    fun stageRemoteTorrentHandoff(bytes: ByteArray, reviewed: PendingLocalAdd?): Boolean {
        // Do not discard a source that a retained Local attempt or its original
        // document handling still owns. Finish that review explicitly first.
        if ((session.attemptedTicket != null && reviewedTorrent.value != null) ||
            session.originalDocument.deleting || session.originalDocument.deletion != null) return false
        if (reviewed != null &&
            (reviewedTorrent.value !== reviewed || !reviewed.stillMatchesReviewedSource(bytes) ||
                !reviewed.stillMatchesReviewedSource(reviewed.bytes))) return false
        remoteSessions.clearPendingSourceSelection()
        sourceDraft.value = ""
        if (reviewed == null) reviewedTorrent.value = null
        serverHandoff = PreparedRemoteTorrent(bytes, reviewed)
        return true
    }

    /** Redirect the current review as a whole, with no new document grant or Add authority. */
    fun prepareCurrentReviewForRemote() {
        val reviewed = reviewedTorrent.value ?: return
        if (serverHandoff?.reviewed !== reviewed && reviewed.stillMatchesReviewedSource(reviewed.bytes)) {
            serverHandoff = PreparedRemoteTorrent(reviewed.bytes, reviewed)
        }
    }

    fun claimServerHandoff(expected: PreparedRemoteTorrent?): Boolean {
        if (expected == null || serverHandoff !== expected) return false
        clearServerHandoff()
        return expected.reviewed === reviewedTorrent.value
    }

    fun clearServerHandoff() { serverHandoff = null }

    fun adoptSharedDocument(reviewed: PendingLocalAdd, forAuthenticatedRemote: Boolean) {
        reviewedTorrent.value = reviewed
        serverHandoff = if (forAuthenticatedRemote) PreparedRemoteTorrent(reviewed.bytes, reviewed) else null
        sourceDraft.value = ""
        remoteSessions.clearPendingSourceSelection()
    }

    fun retireCompletedRemoteReview(completed: PendingLocalAdd?) {
        if (completed == null) return
        if (reviewedTorrent.value === completed) reviewedTorrent.value = null
        if (serverHandoff?.reviewed === completed) clearServerHandoff()
    }

    fun hasDocumentReview(): Boolean =
        (incomingDelivery as? ExternalIntakeDelivery.Add)?.source is ExternalAddSource.TorrentDocument ||
            pendingSharedDocument.value != null ||
            readingSharedDocument.value != null || reviewedTorrent.value?.originalDocument != null
}
