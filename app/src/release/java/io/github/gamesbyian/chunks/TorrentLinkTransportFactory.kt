package io.github.gamesbyian.chunks

/** Release cannot install a controlled proxy or substitute a source transport. */
internal fun defaultTorrentLinkTransport(): TorrentLinkTransport = PinnedTorrentLinkTransport()
