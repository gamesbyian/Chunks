# Research ledger and upstream references

User-voice qualitative reference: [multilingual BitTorrent aversion and friction atlas](BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29.md) (2026-10-29). Descriptive research only, not normative scope or implementation evidence.

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
## October 2026 critical review refinements

- rTorrent command distinction between `d.directory.set` and `d.directory_base.set`: https://rtorrent-docs.readthedocs.io/en/latest/cmd-ref.html
- Documented rTorrent completion scripts combine physical `mv` with directory metadata; not proof of a crash-safe transaction: https://github.com/rakshasa/rtorrent/wiki/Common-Tasks-in-rTorrent
- rTorrent SCGI socket and reverse-proxy boundary: https://github.com/rakshasa/rtorrent-doc/blob/master/RPC-Setup-XMLRPC.md
- Reported ruTorrent compatibility and XMLRPC-C i8 variation: https://github.com/Novik/ruTorrent/issues/2983
- Managed host version compatibility warning (individual deployment evidence, not general rule): https://github.com/MagnaCapax/PMSS/issues/364
- Android foreground service timeouts and special-use policy: https://developer.android.com/develop/background-work/services/fgs/timeout and https://developer.android.com/develop/background-work/services/fgs/service-types

Do not conflate method presence, release number, build/library flags, permission and proven filesystem safety. External source claims become Chunks capabilities only after test evidence.

## Measured interoperability evidence

The rTorrent 0.9.8-1ubuntu0.1 package on an ephemeral Ubuntu GitHub runner, behind nginx localhost SCGI, answered Chunks' read-only Kotlin version, empty main-view list and method enumeration probes successfully in [run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245). This closes a single disposable-server path, not managed seedbox, modern JSON-RPC or authenticated HTTPS interoperability. The owning validation dimensions are in [RTORRENT-COMPATIBILITY-MATRIX.md](RTORRENT-COMPATIBILITY-MATRIX.md).

## Provider-aware connection references (2026-10-28)

This is a source index, **not** a claim that Chunks has connected to all
providers. The active product contract and required acceptance are in
[SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md) and
[RTORRENT-COMPATIBILITY-MATRIX](RTORRENT-COMPATIBILITY-MATRIX.md).

- SeedHost.eu Transdrone path and authentication:
  https://www.seedhost.eu/client-area/knowledgebase/299/Transdrone-basic-configuration.html
  and hosted ruTorrent integration:
  https://www.seedhost.eu/client-area/knowledgebase/281/Prowlarr-basic-configuration.html
- Ultra.cc remote client setup:
  https://docs.ultra.cc/misc-guides/mobile-apps
- Whatbox rTorrent XML-RPC reference:
  https://whatbox.ca/wiki/editing_rtorrentrc
- Swizzin rTorrent documentation:
  https://docs.swizzin.net/applications/rtorrent/
- QuickBox rTorrent application/gateway instructions:
  https://quickbox.io/docs/applications/download-clients/rtorrent
- Feral Hosting Transdrone example:
  https://www.feralhosting.com/wiki/software/transdrone
- RapidSeedbox ruTorrent RPC plugin instructions:
  https://help.rapidseedbox.com/en/articles/8504545-connecting-your-home-sonarr-radarr-to-rsb-s-deluge-and-rutorrent
- GigaDrive mobile BitTorrent client instructions:
  https://docs.giga-rapid.com/en/guides/beginners/how-to-control-your-bittorrent-client-using-mobile-apps
- Appbox ruTorrent/Transdroid:
  https://billing.appbox.co/knowledgebase/166/ruTorrent-and-Transdroid.html?language=english
- Seedbox.io Transdroid RPC variant:
  https://docs.seedbox.io/shared-storage/applications/transdroid/
- Self-hosted direct XML-RPC security/reference:
  https://github.com/rakshasa/rtorrent-doc/blob/master/RPC-Setup-XMLRPC.md

Provider documentation can be version-, plan- and deployment-specific.
Keep a review date and measured sample for every rule. Distinguish
*known browser URL*, *actual RPC mount*, *HTTP authentication principal*,
*service-account/slot username*, and *instance*. Do not construct a
hostname from a username or silently replace an explicit URL with a
different instance; do not assume a bare `/RPC2` is globally supported.
