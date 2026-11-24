package io.github.gamesbyian.chunks

/**
 * In-memory instrumentation seam only, compiled in debug and qaMinified.
 * No Intent, preference, UI, network or manifest entry can set this value.
 * Default debug APK behavior uses the identical pinned production transport.
 */
internal object DebugTorrentLinkTransportOverride {
    @Volatile var transport: TorrentLinkTransport? = null
}

internal fun defaultTorrentLinkTransport(): TorrentLinkTransport =
    DebugTorrentLinkTransportOverride.transport ?: PinnedTorrentLinkTransport()
