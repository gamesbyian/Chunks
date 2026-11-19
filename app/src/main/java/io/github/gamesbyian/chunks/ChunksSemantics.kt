package io.github.gamesbyian.chunks

/**
 * Stable semantic identifiers for *enduring product capabilities*.
 *
 * Tests find a control by what it is for, not by its current wording, position or
 * which screen hosts it. A capability keeps its identifier when the UI is redesigned,
 * and the same identifier is used by every surface that offers that capability (for
 * example the local device and the authenticated-server Add forms share one source field id).
 *
 * Rules (see docs/TEST-ARCHITECTURE.md):
 * - Add an identifier only for a capability the product specification requires.
 * - Never encode copy, layout, order, environment or a screen name in an identifier.
 * - An identifier says nothing about enabled/selected/error state; tests assert that
 *   state through Compose semantics (`assertIsEnabled`, `assertIsSelected`, state
 *   description) so accessibility and test checks stay the same thing.
 * - Retiring a capability means deleting its identifier in the same change that
 *   records the requirement's replacement verification in docs/TEST-ARCHITECTURE.md.
 */
internal object ChunksSemantics {
    // Persistent root navigation (one footer row).
    const val FOOTER_TRANSFERS = "chunks.footer.transfers"
    const val FOOTER_ADD = "chunks.footer.add"
    const val FOOTER_CONNECTIONS = "chunks.footer.connections"
    const val FOOTER_OPTIONS = "chunks.footer.options"

    const val CONNECTION_ADD = "chunks.connection.add"
    /** The one verified session's "View transfers" roster action (its name says which client). */
    const val CONNECTION_VIEW_TRANSFERS = "chunks.connection.view_transfers"
    /** Submit a reviewed connection; stable across keyboard and roster layout. */
    const val CONNECTION_CONNECT = "chunks.connection.connect"
    const val PROTOCOL_OPERATIONS = "chunks.protocol.operations"
    const val PROTOCOL_START_STOP = "chunks.protocol.start_stop"
    const val PROTOCOL_REMOVE = "chunks.protocol.remove"
    const val PROTOCOL_REMOVE_CONFIRM = "chunks.protocol.remove.confirm"
    const val PROTOCOL_OPERATION_STATUS = "chunks.protocol.operation_status"
    const val PROTOCOL_MOVE = "chunks.protocol.move"
    const val PROTOCOL_MOVE_RETIRE = "chunks.protocol.move.retire"
    const val PROTOCOL_MOVE_SUGGESTION = "chunks.protocol.move.suggestion"
    const val PROTOCOL_SET_LOCATION = "chunks.protocol.set_location"
    const val PROTOCOL_SET_LOCATION_FOLDER = "chunks.protocol.set_location.folder"
    const val PROTOCOL_SET_LOCATION_MOVE_FILES = "chunks.protocol.set_location.move_files"
    const val PROTOCOL_SET_LOCATION_CONFIRM = "chunks.protocol.set_location.confirm"
    const val CONNECTION_NAME = "chunks.connection.name"

    // Universal Add: one source, one submission action, one Start preference.
    const val ADD_SOURCE_FIELD = "chunks.add.source"
    /** The shared Universal Add "Open .torrent" action. */
    const val ADD_OPEN_TORRENT = "chunks.add.openTorrent"
    /** Root of the rTorrent Add body (Save to / options / receipt for an authenticated server). */
    const val ADD_BODY_RTORRENT = "chunks.add.body.rtorrent"
    /** Root of the Transmission/qBittorrent/Deluge Add body. */
    const val ADD_BODY_PROTOCOL = "chunks.add.body.protocol"
    const val ADD_SUBMIT = "chunks.add.submit"
    /** In-place remote Add status: submitting, verified, refused or uncertain. */
    const val ADD_REMOTE_OPERATION_STATUS = "chunks.add.remote.operationStatus"
    /** Read-only exact-hash status check of an unresolved Add; never a second write. */
    const val ADD_CHECK_STATUS = "chunks.add.checkStatus"
    const val ADD_FOREIGN_PARTIAL = "chunks.add.foreign_partial"
    /** Per-server opt-in for remote Smart Seeding rules (default off). */
    const val SEEDING_REMOTE_ENFORCEMENT = "chunks.seeding.remoteEnforcement"
    const val ADD_ORIGINAL_HANDLING = "chunks.add.original_handling"
    /** Read-only effective policy line on Add; the choice itself lives in Options. */
    const val ADD_ORIGINAL_HANDLING_SUMMARY = "chunks.add.original_handling.summary"
    const val OPTIONS_ADD_AND_FILES = "chunks.options.add_and_files"
    const val REMOTE_FOLDER_NEW_DISABLED = "chunks.remote_folder.new.disabled"
    const val REMOTE_FOLDER_RENAME_UNAVAILABLE = "chunks.remote_folder.rename.unavailable"
    const val ADD_FILE_CHOICE = "chunks.add.file_choice"
    /** Prefix; suffixed with ".<metainfo index>": one reviewed file's Add-time priority. */
    const val ADD_FILE_PRIORITY = "chunks.add.file_priority"
    const val ADD_ORIGINAL_DELETE_PROMPT = "chunks.add.original_delete.prompt"
    const val ADD_ORIGINAL_DELETE_CONFIRM = "chunks.add.original_delete.confirm"
    const val ADD_FOREIGN_PARTIAL_REUSE = "chunks.add.foreign_partial.reuse"
    const val DETAILS_FOREIGN_PARTIAL = "chunks.details.foreign_partial"
    const val DETAILS_FOREIGN_PARTIAL_REUSE = "chunks.details.foreign_partial.reuse"
    const val DETAILS_LOCAL_RECHECK = "chunks.details.local.recheck"
    const val DETAILS_LOCAL_FILES = "chunks.details.local.files"
    const val DETAILS_LOCAL_REMOVE = "chunks.details.local.remove"
    const val DETAILS_LOCAL_REMOVE_DATA = "chunks.details.local.remove-data"
    const val DETAILS_LOCAL_OUTCOME = "chunks.details.local.outcome"
    const val DETAILS_LOCAL_TRACKERS = "chunks.details.local.trackers"
    const val DETAILS_REMOTE_RECHECK = "chunks.details.remote.recheck"
    const val REMOTE_RECHECK_STATUS = "chunks.remote.recheck.status"
    /** Exact Add-draft artwork choice, independent of its disclosure label. */
    const val ADD_ARTWORK_CHOICE = "chunks.add.artwork-choice"
    const val ADD_START_PREFERENCE = "chunks.add.start-preference"
    /** Inline Add-to choice for this device. Selected state is a Compose semantic. */
    const val ADD_TARGET_DEVICE = "chunks.add.target.device"
    /** Inline Add-to choice for the one authenticated remote profile. */
    const val ADD_TARGET_REMOTE = "chunks.add.target.remote"
    /** The always-visible row of per-environment show/hide toggles above Transfers. */
    const val TRANSFERS_SEARCH = "chunks.transfers.search"
    const val TRANSFERS_ENVIRONMENT_TOGGLES = "chunks.transfers.environmentToggles"
    /** Search-adjacent disclosure for existing filter, sort and grouping controls. */
    const val TRANSFERS_FILTER_SORT = "chunks.transfers.filterSort"
    /** Search-adjacent disclosure for existing exact-job batch review. */
    const val TRANSFERS_BATCH_TOOLS = "chunks.transfers.batchTools"
    /** An expanded Transfers row's quick operations (Start/Stop). */
    const val TRANSFER_ROW_QUICK_CONTROLS = "chunks.transfers.row.quickControls"
    /** Remote Add effective Save to status (custom, saved default, rTorrent default or why none). */
    const val ADD_REMOTE_SAVE_TO = "chunks.add.remote.saveTo"
    /** Remote Add "Always use this folder on this server" (saved per-account default). */
    const val ADD_REMOTE_SAVE_DEFAULT = "chunks.add.remote.saveDefault"
    /** Observable pending/settled state for the existing exact-server removal control. */
    const val REMOTE_REMOVAL_STATUS = "chunks.remote.removal.status"

    /** Canonical list surface; its screen name is semantic, without a redundant visible title. */
    const val TRANSFERS_SCREEN = "chunks.transfers.screen"
    const val DETAILS_SCREEN = "chunks.details.screen"

    // First-install Welcome.
    const val WELCOME_HEADING = "chunks.welcome.heading"
    const val WELCOME_ADD = "chunks.welcome.add"
    const val WELCOME_CONNECTIONS = "chunks.welcome.connections"
}
