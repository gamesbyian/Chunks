package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview

/**
 * Universal Add's Smart Routing decisions as pure functions (A2611-02: Add routing consumer).
 * `ChunksApp` keeps only the state it owns (the loaded policy, the draft, the selected target)
 * and passes it in; none of these functions reads composition state, touches storage or
 * performs a write. A suggestion never selects a target by itself: the person taps it, or the
 * explicit opt-in pre-selects it once per reviewed source through
 * [SmartRoutingAddSuggestion.shouldPreselect].
 */
internal object AddRouting {
    /** Where adopting a suggestion would move the draft. */
    sealed interface Target {
        data object Local : Target
        data object AuthenticatedRTorrent : Target
        data class Protocol(val profileId: String) : Target
    }

    /**
     * The reviewed source whose own names routing may evaluate. A retained file is evidence
     * only while it is the active source: rTorrent can explicitly reselect its reviewed file over
     * the same text ([remoteFileStillCurrent]); an empty draft falls back to Local's reviewed file;
     * a server source still being prepared has no source yet.
     */
    fun source(
        preparingRemoteSource: Boolean,
        addTargetIsAuthenticated: Boolean,
        remoteReviewedFile: TorrentMetainfoPreview?,
        remoteFileStillCurrent: Boolean,
        draftBlank: Boolean,
        localReviewedFile: TorrentMetainfoPreview?
    ): TorrentMetainfoPreview? = when {
        preparingRemoteSource -> null
        addTargetIsAuthenticated -> remoteReviewedFile.takeIf { remoteFileStillCurrent }
        draftBlank -> localReviewedFile
        else -> null
    }

    /** One candidate per environment that could take this Add; Local is always live. */
    fun candidates(
        connectedRTorrentId: String?,
        rTorrentLabel: String?,
        rTorrentLive: Boolean,
        rTorrentPlaceKnown: Boolean,
        protocolProfiles: List<CombinedReadOnlyProtocolProfile>,
        protocolSessions: Map<String, ClientSessionInfo>,
        magnetSource: Boolean,
        startNow: Boolean
    ): List<SmartRoutingAddSuggestion.Candidate> = buildList {
        add(SmartRoutingAddSuggestion.Candidate(EnvironmentSnapshots.LOCAL.value, "Local", true, true, true))
        connectedRTorrentId?.let {
            add(SmartRoutingAddSuggestion.Candidate(it, rTorrentLabel ?: "rTorrent", live = rTorrentLive,
                acceptsAdd = true, placeKnown = rTorrentPlaceKnown))
        }
        protocolProfiles.filter { it.operations != null }.forEach {
            add(SmartRoutingAddSuggestion.candidateFor(it.id, it.label, protocolSessions[it.id],
                magnetSource = magnetSource, startNow = startNow))
        }
    }

    /**
     * The target a suggestion for [suggestedId] selects, or null when it must not move the draft:
     * no suggestion, an environment that is not (or is no longer) a connected Add target, or a
     * protocol client while a different protocol Add is in flight.
     */
    fun targetFor(
        suggestedId: String?,
        connectedRTorrentId: String?,
        protocolProfiles: List<CombinedReadOnlyProtocolProfile>,
        protocolAddBusy: Boolean,
        protocolAddProfileId: String?
    ): Target? = when {
        suggestedId == null -> null
        suggestedId == EnvironmentSnapshots.LOCAL.value -> Target.Local
        suggestedId == connectedRTorrentId -> Target.AuthenticatedRTorrent
        protocolProfiles.any { it.id == suggestedId && it.operations != null } &&
            (!protocolAddBusy || protocolAddProfileId == suggestedId) -> Target.Protocol(suggestedId)
        else -> null
    }

    /** Whether the suggested client is already the draft's selected Add target. */
    fun isSelected(
        suggestedId: String?,
        selectedProtocolId: String?,
        addTargetIsAuthenticated: Boolean,
        connectedRTorrentId: String?,
        localSelected: Boolean
    ): Boolean = suggestedId?.let { id ->
        id == selectedProtocolId ||
            (selectedProtocolId == null && addTargetIsAuthenticated && id == connectedRTorrentId) ||
            (localSelected && id == EnvironmentSnapshots.LOCAL.value)
    } == true
}
