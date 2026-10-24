# Research ledger and upstream references
2026-10-23 exploratory research. References are upstream docs, not proofs of Chunks functionality. Verify licenses, APIs and current versions before incorporating code.

- rTorrent docs and command reference: https://rtorrent-docs.readthedocs.io/en/latest/ and https://kannibalox.github.io/rtorrent-docs/cmd-ref.html
- rTorrent XML-RPC setup and SCGI risk: https://github.com/rakshasa/rtorrent-doc/blob/master/RPC-Setup-XMLRPC.md
- rTorrent-rpc Python client including JSON-RPC capability: https://rtorrent-rpc.readthedocs.io/
- Transdroid rTorrent remote reference: https://github.com/erickok/transdroid
- Flood common backend/remote file operations reference: https://github.com/jesec/flood
- ruTorrent: https://github.com/Novik/ruTorrent
- LibreTorrent Android + local engine reference: https://github.com/proninyaroslav/libretorrent
- BiglyBT Android: https://github.com/BiglySoftware/BiglyBT-Android
- Arvid libtorrent docs: https://libtorrent.org/reference-Torrent_Handle.html
- Android storage: https://developer.android.com/training/data-storage/shared/documents-files
- Android foreground service restrictions: https://developer.android.com/develop/background-work/services/fgs
- Compose accessibility: https://developer.android.com/develop/ui/compose/accessibility
- GuessIt filename parsing: https://github.com/guessit-io/guessit
- MusicBrainz and Open Library metadata: https://musicbrainz.org/doc/MusicBrainz_API and https://openlibrary.org/developers/api
- Sonarr hardlink/import semantics: https://wiki.servarr.com/sonarr/settings

Known distinctions: rTorrent uses rakshasa libTorrent, not Arvid libtorrent. Metadata changing a torrent directory does not move actual bytes. Local and remote capabilities must be probed. Compatibility and Android background claims remain testable hypotheses until real evidence is gathered.

Competitive UX targets: Flud/LibreTorrent/BiglyBT (local clients), Transdroid/nzb360 (remote), ruTorrent/Flood (rTorrent and file operations), Transmission/qBittorrent (desktop expectations). Research must capture concrete reproducible complaints, not cherry-picked reviews. Licensing and dependency attribution remain open.