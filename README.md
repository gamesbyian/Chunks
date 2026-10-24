# chunks.

**Your downloads have places to be.**

Chunks is an Android-native BitTorrent client and first-class rTorrent remote. The product unifies **Transfers**, **Places** (storage destinations), and **Collections** (semantic groupings) so users can add, move, rename, and organize content without losing seeding integrity.

## Status

**Pre-alpha / Android remote dashboard prototype.** The repository originally contained only a one-line README. An Android debug APK now provides protected read-only rTorrent connection, list/search/filter/sort, file inspection, discovered Places and local-only collection suggestions, plus a destination-first magnet Add **preview** that accepts Android Share/Open With magnet links. No remote Add, pause/start, moving, renaming, durable login, or local torrent engine is shipped. No live server or handset test is accepted. Current build and test claims are tracked in [the evidence ledger](docs/QUALITY-LEDGER.md); plans are not evidence.

## Read in this order

1. [Agent operating contract](AGENTS.md)
2. [Critical design review](docs/CRITICAL-REVIEW-2026-10-24.md) and [product contract](docs/PRODUCT.md)
3. [Live work queue](docs/WORK-QUEUE.md) and [GitHub issues](https://github.com/gamesbyian/Chunks/issues)
4. [Architecture and failure semantics](docs/ARCHITECTURE.md)
5. [rTorrent real-server compatibility matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md), [engineering decisions](docs/DECISIONS.md), [independent acceptance](docs/QUALITY-LEDGER.md), [CI policy](docs/CI-POLICY.md)
6. [Research and upstream references](docs/RESEARCH.md), [cross-project reuse audit](docs/REUSE-AUDIT.md), [security](docs/SECURITY.md), [interaction journeys](docs/UX.md)

Every feature must distinguish **requested**, **acknowledged**, and **verified** outcomes. Neither success on an emulator nor a green Gradle check proves a real seedbox operation, storage safety, Android background reliability, or an accessible physical UI.

Repository: https://github.com/gamesbyian/Chunks

No third-party service, telemetry, account, or remote metadata lookup is required by the product concept. Licensing and release distribution remain undecided; public repository visibility does not grant permission to redistribute code.
## Hosted ruTorrent connection

The connection form accepts either an HTTPS rTorrent XML-RPC endpoint or a hosted ruTorrent web URL such as `https://host.example/rutorrent/`. It derives the candidate HTTP-RPC plugin path automatically. See [provider testing and secrets policy](docs/SEEDHOST-REMOTE-TESTING.md). Chunks does not save credentials, and real protected seedbox interoperability remains unverified.
