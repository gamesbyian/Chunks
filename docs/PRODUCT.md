# Chunks canonical product contract
Status: committed product direction, engineering details subject to proof. 2026-10-23.

## Thesis
Chunks makes downloading, moving, renaming and organizing torrents one continuous activity. Android-native, irreverent, colourful, generous targets, sophisticated under the surface. Never force a user to think in protocol terminology to perform a normal file operation.

## Three nouns
- **Transfer**: stable content/torrent identity and the local or remote engine session; may contain many files.
- **Place**: a concrete, access-checked storage destination on a particular device/server and volume, plus aliases/favorites/history.
- **Collection**: meaningful cross-media grouping (TV, movies, Star Trek, comics, Spider-Man, albums, books); independent of Places and file names.

Keep views/tags/rules separate from physical paths. A torrent may belong to several collections while existing in one or more Places.

## Primary journeys
1. Share a magnet/torrent into Chunks → select Local or rTorrent server → see recent/favorite/new Place → inspect files and space/capabilities → explicitly start.
2. While downloading, choose Move → preview original/destination path and whether data must copy → journal the operation → reconcile and verify result before success.
3. Rename one torrent-managed file or create a differently named library copy → explain seeding implications → verify engine mapping/content integrity.
4. Open Organize → see transparent, confidence-rated suggested categories/Places/new folders and reasons → accept one, batch accept, convert to rule or dismiss. Automatic operation only after explicit scoped rule approval.
5. Remote setup → protected connection with auth, version/protocol probe and diagnostics → list/control transfers → reconnect after interruption without duplicate mutations.
6. Expert operations → inspect trackers, peers, priorities, ratios, queue, speed, file availability, error logs; accessible without dominating casual home UI.

## Capability policy
Local and remote share typed commands, not fictional feature parity. Distinguish native, companion-assisted, safe fallback and unsupported actions. Standard rTorrent RPC should work without Chunks Companion; file-manager-like operations may require it. Never claim no-interruption throughput for cross-filesystem moves; aim for session continuity and reversible interruption.

## Visual contract
Dark electric and light playful palettes are proposals, not approved design. 48dp minimum interactive targets, responsive densities, high contrast, TalkBack/keyboard, reduced motion, visible status/error recovery, one-hand priority. Irreverence belongs in discovery and empty states, not errors or destructive confirmation.

## Non-goals initially
Mandatory cloud accounts, streaming catalog, tracker index/search, piracy content discovery, opaque AI uploads, universal remote filesystem access through RPC alone, full Plex/Sonarr/Radarr integration before core operations are safe.