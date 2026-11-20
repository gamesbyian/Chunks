package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo

/**
 * What the ONE Universal Add form may offer for the selected exact client.
 * A client is a destination with capabilities, not a separate journey: the
 * shared form shows, explains in place, or withholds each control from this
 * descriptor. It never authorizes a write; admission, tickets and the
 * engine's own preflight stay authoritative. Unknown means not offered.
 */
internal data class UniversalAddClientCapabilities(
    val magnet: Boolean,
    val torrentFile: Boolean,
    /** Can add without starting payload (required for Start off and for pre-Start file choices). */
    val stoppedAdd: Boolean,
    /** An exact folder other than the client's default. */
    val customFolder: Boolean,
    /** Per-file Skip at Add, applied before any Start. */
    val fileChoices: Boolean,
    /** Add-time Low/High levels the client stores exactly (never a silent Normal). */
    val priorityLevels: Set<ClientFilePriority>,
    /** Finish in Order can be chosen in the Add form. */
    val finishInOrderAtAdd: Boolean,
    /** A Chunks display name is stored and shown for this client's transfers. */
    val displayName: Boolean,
    /** The per-Add artwork consent choice applies to this client. */
    val artworkChoice: Boolean,
    /** The global original-.torrent Keep/Archive/Delete policy is applied after a verified new Add. */
    val originalSourcePolicy: Boolean
) {
    /** Engine-neutral explanation when this client cannot take the reviewed source, or null. */
    fun refusal(isMagnet: Boolean, startNow: Boolean, clientLabel: String): String? = when {
        isMagnet && !magnet -> "$clientLabel cannot add magnet links."
        !isMagnet && !torrentFile -> "$clientLabel cannot add .torrent files."
        !startNow && !stoppedAdd -> "$clientLabel cannot add this kind of source stopped."
        else -> null
    }

    companion object {
        private val BOTH_LEVELS = setOf(ClientFilePriority.LOW, ClientFilePriority.HIGH)

        /** The device's own libtorrent: every Add choice, from its one native sequence. */
        val LOCAL = UniversalAddClientCapabilities(
            magnet = true, torrentFile = true, stoppedAdd = true, customFolder = true,
            fileChoices = true, priorityLevels = BOTH_LEVELS, finishInOrderAtAdd = true,
            displayName = true, artworkChoice = true, originalSourcePolicy = true)

        /** An authenticated rTorrent account through its stopped-first load and checked priorities. */
        val RTORRENT = UniversalAddClientCapabilities(
            magnet = true, torrentFile = true, stoppedAdd = true, customFolder = true,
            fileChoices = true, priorityLevels = setOf(ClientFilePriority.HIGH), finishInOrderAtAdd = true,
            displayName = true, artworkChoice = true, originalSourcePolicy = true)

        /**
         * A Transmission/qBittorrent/Deluge profile from its own authenticated
         * session probe. Before the probe answers, nothing is offered.
         */
        fun protocol(session: ClientSessionInfo?): UniversalAddClientCapabilities {
            val caps = session?.capabilities.orEmpty()
            val stopped = ClientCapability.ADD_STOPPED in caps
            return UniversalAddClientCapabilities(
                magnet = ClientCapability.ADD_MAGNET in caps,
                torrentFile = ClientCapability.ADD_TORRENT_FILE in caps,
                stoppedAdd = stopped,
                customFolder = ClientCapability.ADD_TO_DIRECTORY in caps,
                // Skips are written while still stopped, then Start: both are required.
                fileChoices = ClientCapability.FILE_PRIORITIES in caps && stopped,
                priorityLevels = BOTH_LEVELS.filter { it in session?.filePriorities.orEmpty() }.toSet(),
                finishInOrderAtAdd = false,
                // Stored under the exact profile's ProtocolAliasScope after a verified new Add.
                displayName = true,
                artworkChoice = true,
                originalSourcePolicy = true)
        }
    }
}
