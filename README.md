# chunks.

**Your downloads have places to be.**

Chunks is an Android-native BitTorrent client and first-class rTorrent remote. The product unifies **Transfers**, **Places** (storage destinations), and **Collections** (semantic groupings) so users can add, move, rename, and organize content without losing seeding integrity.

## Status

**Pre-alpha / research-backed foundation.** The repository originally contained only a one-line README. Neither a full local BitTorrent engine, a robust rTorrent remote, nor a verified relocation system has shipped. Current build and test claims are tracked in [the evidence ledger](docs/QUALITY-LEDGER.md); plans are not evidence.

## Read in this order

1. [Agent operating contract](AGENTS.md)
2. [Critical design review](docs/CRITICAL-REVIEW-2026-10-24.md) and [product contract](docs/PRODUCT.md)
3. [Live work queue](docs/WORK-QUEUE.md) and [GitHub issues](https://github.com/gamesbyian/Chunks/issues)
4. [Architecture and failure semantics](docs/ARCHITECTURE.md)
5. [Engineering decisions](docs/DECISIONS.md), [independent acceptance](docs/QUALITY-LEDGER.md), [CI policy](docs/CI-POLICY.md)
6. [Research and upstream references](docs/RESEARCH.md), [cross-project reuse audit](docs/REUSE-AUDIT.md), [security](docs/SECURITY.md), [interaction journeys](docs/UX.md)

Every feature must distinguish **requested**, **acknowledged**, and **verified** outcomes. Neither success on an emulator nor a green Gradle check proves a real seedbox operation, storage safety, Android background reliability, or an accessible physical UI.

Repository: https://github.com/gamesbyian/Chunks

No third-party service, telemetry, account, or remote metadata lookup is required by the product concept. Licensing and release distribution remain undecided; public repository visibility does not grant permission to redistribute code.