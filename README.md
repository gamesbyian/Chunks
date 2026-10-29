# chunks.

**Your downloads have places to be.**

Chunks is an Android-native BitTorrent client and first-class rTorrent remote control. It aims to make torrent management powerful without making file locations, queue controls, storage integrity or server administration needlessly complicated.

**Development status: pre-alpha.** Merged, tested slices exist, but full physical-device, supported-server, security, release-signing and accessibility acceptance is still incomplete. Do not infer feature readiness from a screenshot, PR title or aspirational specification. See [live coverage](docs/SPEC-COVERAGE.md), [test evidence](docs/QUALITY-LEDGER.md) and the [active work queue](docs/WORK-QUEUE.md).

## One experience, your choice of environments

Chunks supports three intended configurations:

- **On-phone client only:** the default first-run offer, with no rTorrent connection required.
- **rTorrent remote only:** control configured seedbox/server profiles without requiring local torrent-engine setup.
- **Both:** switch among phone and one or more independently configured rTorrent instances while using a consistent Transfers list, details, Add, controls, Collections and settings model.

Each environment has its own torrent identities, connection/storage capabilities and appropriate settings. Lists and transfer details identify the active phone/server. Switching is UI navigation and does not move files or torrent jobs. [Binding environment contract](docs/UNIFIED-ENVIRONMENTS.md).

## Fundamental concepts

- **Transfer:** one torrent job managed by a specific phone/server engine, with progress, controls and an exact scoped infohash.
- **Place:** an actual permitted download or library destination, not a cosmetic label.
- **Collection:** a user-defined semantic group that does not move files merely by including a transfer.
- **Display alias:** an optional Chunks-only title, distinct from torrent metadata, downloaded files and actual paths.
- **Library copy/extracted output:** a separately tracked file object; neither is permission to delete the original seedable bytes.

**Copy Transfer** and **Move Transfer** hand off torrent *jobs only*, including batches. Neither copies downloaded payload bytes. Move separately verifies the target job and asks before removing only the source job; existing source files remain. File copying, physical relocation, extraction and deletion are different explicitly labelled operations. [Binding job handoff contract](docs/CROSS-ENGINE-TRANSFER-JOBS.md).

## Local torrent client

The libtorrent4j-based local engine has implemented and tested slices for torrent/magnet intake, peer and web-seed transfers, resume, selected file priorities, speed limits, DHT opt-in and foreground-service integration. Android public Downloads/Chunks Downloads support has bounded Android/API acceptance; arbitrary providers, SD cards, uninterrupted long-running mobile background seeding and release-quality physical-handset behavior need independent verification.

On Add, Chunks must show a valid destination and optional Change/recent selections without requiring a picker. Download data and detached copies retain distinct ownership. Physical file management, quotas and cleanup obey [storage and extraction](docs/STORAGE-AND-EXTRACTION.md) safety rules.

## rTorrent remote control

The remote adapter uses authenticated HTTPS XML-RPC and scoped capabilities for connection, transfer inspection, Add, Start/Stop, priorities and safe session-only removal. **Smart Connect** should normally allow a seedbox hostname or exact ruTorrent instance URL and credentials, while retaining an advanced custom endpoint path. Instance identity, TLS, permissions and uncertain RPC mutations must be handled explicitly. A network success alone is not proof that a torrent or file operation succeeded.

Multiple server profiles and provider variants require independent testing; provider catalog entries are hints, not promises of compatibility. See the [Smart Connect contract](docs/SMART-REMOTE-CONNECTION.md), [rTorrent matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md), and [approved Seedhost testing guide](docs/SEEDHOST-REMOTE-TESTING.md). Incoming BitTorrent peer ports and the HTTPS remote-control port serve different purposes.

## Further planned experience

The canonical [product specification](docs/PRODUCT.md) and [UX contract](docs/UX.md) cover deliberate file-completion ordering, multi-format metadata/artwork across media categories, transparent alias and Collection workflows, accessibility, per-environment privacy lock, safe storage budgets and optional archive extraction. Features have different implementation and acceptance statuses; [SPEC-COVERAGE](docs/SPEC-COVERAGE.md) is the starting point for current scope.

## Build and contribute

Use JDK 17, Android SDK and Gradle 9.3.1, following [BUILD](docs/BUILD.md). For a developer build:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The result is a debug APK, not a signed independent release. Installer distribution, signing and licensing remain subject to [release issue #10](https://github.com/gamesbyian/Chunks/issues/10).

Agents and maintainers **start with [AGENTS.md](AGENTS.md)** and the [documentation index](docs/INDEX.md). The [documentation standard](docs/DOCUMENTATION-STANDARD.md) defines which files are authoritative, how to distinguish requirements from evidence, and how to avoid duplicate plans. Check repository-local documentation links without CI fan-out:

```sh
python3 tools/check_docs.py
python3 -m unittest discover -s tests -p 'test_docs_check.py'
```

App development and the independently governed font laboratory have separate source and CI policies. See [CI-POLICY](docs/CI-POLICY.md) and [font-lab operations](docs/design/CHUNKS-FONT-LAB-OPERATIONS.md).

## Privacy and safe testing

Never publish credentials, remote auth cookies, tracker passkeys or sensitive torrents through screenshots, diagnostics or external metadata requests. Test Adds with approved [licensed public fixtures](docs/PUBLIC-TORRENT-FIXTURES.md); the owner's approved development Seedhost test permissions do **not** extend to arbitrary hosts or backing-file deletion. Mutations are exact-target, capability-gated, journaled where necessary and verified by observed postconditions; an uncertain command is reconciled rather than blindly repeated.

The dated prior project overview is [archived for traceability](docs/archive/README-2026-10-29-preconsolidation.md). The archive is not a competing specification.
