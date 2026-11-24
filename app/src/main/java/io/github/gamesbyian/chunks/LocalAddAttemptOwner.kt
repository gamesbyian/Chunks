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
    val serverHandoffTorrent = mutableStateOf<ByteArray?>(null)
    val serverHandoffDocument = mutableStateOf<Uri?>(null)
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

    fun hasDocumentReview(): Boolean =
        (incomingDelivery as? ExternalIntakeDelivery.Add)?.source is ExternalAddSource.TorrentDocument ||
            pendingSharedDocument.value != null ||
            readingSharedDocument.value != null || reviewedTorrent.value?.originalDocument != null
}
