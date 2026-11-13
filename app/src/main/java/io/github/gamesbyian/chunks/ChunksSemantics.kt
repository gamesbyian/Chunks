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
    const val CONNECTION_NAME = "chunks.connection.name"

    // Universal Add: one source, one submission action, one Start preference.
    const val ADD_SOURCE_FIELD = "chunks.add.source"
    const val ADD_SUBMIT = "chunks.add.submit"
    const val ADD_FOREIGN_PARTIAL = "chunks.add.foreign_partial"
    const val ADD_ORIGINAL_HANDLING = "chunks.add.original_handling"
    const val ADD_ORIGINAL_DELETE_PROMPT = "chunks.add.original_delete.prompt"
    const val ADD_ORIGINAL_DELETE_CONFIRM = "chunks.add.original_delete.confirm"
    const val ADD_FOREIGN_PARTIAL_REUSE = "chunks.add.foreign_partial.reuse"
    const val DETAILS_FOREIGN_PARTIAL = "chunks.details.foreign_partial"
    const val DETAILS_FOREIGN_PARTIAL_REUSE = "chunks.details.foreign_partial.reuse"
    const val DETAILS_LOCAL_RECHECK = "chunks.details.local.recheck"
    const val DETAILS_LOCAL_FILES = "chunks.details.local.files"
    const val DETAILS_LOCAL_REMOVE = "chunks.details.local.remove"
    const val DETAILS_LOCAL_REMOVE_DATA = "chunks.details.local.remove-data"
    const val ADD_START_PREFERENCE = "chunks.add.start-preference"
    /** Inline Add-to choice for this device. Selected state is a Compose semantic. */
    const val ADD_TARGET_DEVICE = "chunks.add.target.device"
    /** Inline Add-to choice for the one authenticated remote profile. */
    const val ADD_TARGET_REMOTE = "chunks.add.target.remote"
    /** The always-visible row of per-environment show/hide toggles above Transfers. */
    const val TRANSFERS_ENVIRONMENT_TOGGLES = "chunks.transfers.environmentToggles"
    /** An expanded Transfers row's quick operations (Start/Stop). */
    const val TRANSFER_ROW_QUICK_CONTROLS = "chunks.transfers.row.quickControls"
    /** Remote Add effective Save to status (custom, saved default, rTorrent default or why none). */
    const val ADD_REMOTE_SAVE_TO = "chunks.add.remote.saveTo"
    /** Remote Add "Always use this folder on this server" (saved per-account default). */
    const val ADD_REMOTE_SAVE_DEFAULT = "chunks.add.remote.saveDefault"

    /** The canonical Transfers list title. It also hosts the first-install Welcome. */
    const val TRANSFERS_HEADING = "chunks.transfers.heading"

    // First-install Welcome.
    const val WELCOME_HEADING = "chunks.welcome.heading"
    const val WELCOME_ADD = "chunks.welcome.add"
    const val WELCOME_CONNECTIONS = "chunks.welcome.connections"
}
