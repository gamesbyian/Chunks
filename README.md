# chunks.

> **Owner correction (2026-10-28):** Cross-engine **Copy Transfer** and **Move Transfer** operate on torrent **jobs only**, never downloaded file bytes. Move adds and verifies the target job, then separately confirms session-only removal of the source job, leaving all source files untouched. Destination downloads independently. Prior text proposing payload transfer, file migration or waiting for target seeding under these names is superseded by [the authoritative transfer-job contract](docs/CROSS-ENGINE-TRANSFER-JOBS.md).

**Your downloads have places to be.**

Chunks is an Android-native BitTorrent client and first-class rTorrent remote. Its Transfers represent torrent jobs; Places represent **actual** server/device destinations and their permissions; Collections are semantic groups. Original torrent names, Chunks-only display aliases, actual backing file paths and independent library copies are distinct so that a cosmetic rename never breaks seeding.

## Status: pre-alpha (October 26, 2026)

**Merged, functional engineering slices exist, but release readiness is not established.**

- **rTorrent remote:** Authenticated HTTPS XML-RPC, Basic/Digest, bounded/paged transfer listing, search/filter/sort and details, per-file priority, verified stopped Add from a v1 magnet or Android .torrent file to a chosen **parent destination**, Start/Stop and exact-hash **session-only** Remove. Development Seedhost rTorrent 0.9.8 with 437 transfers has scoped live adapter Add/read/control evidence. Legacy disposable 0.9.8 and modern Arch 0.16.x real adapter tests pass, but Alpine 0.15.2 SCGI fails (#151). Full real Android handset-to-Seedhost acceptance remains open.
- **Local client:** Experimental libtorrent4j engine with resume, explicit download/save directory in app-private storage, metadata magnets, real licensed web-seed download, opt-in DHT, file priority, speed limits and Android dataSync foreground service. A completed file can be **copied** to chosen SAF library storage, keeping the original available to seed. #154 improves transient engine-read error handling. Neither direct arbitrary SAF download nor reliable Doze/OEM/background physical phone behavior has been proven.
- **Organization:** Editable, reversible local display aliases, source-only explained clean-title proposals and limited batch review/conditional Undo. No automated physical backing rename or unsourced episode guest-name inference.
- **Storage and ZIP:** Advisory storage caps/warnings/cleanup-impact models and a read-only phone-space card. Bounded local ZIP preflight and **manual verified extraction into a newly created SAF folder**, with cancel/journal/recovery and source kept intact, have tested emulator/fixture-provider paths. No real quota enforcement, destructive cleanup wizard, general remote file operations or auto-extract. Physical provider and process-kill acceptance is pending.

## Documentation and engineering

**Visual design:** The [canonical transparent Chunks app icon](docs/design/approved/chunks-app-icon-master.svg) is the SVG source of truth; the [original PNG](docs/design/approved/chunks-app-icon-master.png) remains reference-only. Android packages a generated native VectorDrawable rather than the PNG (see [BUILD](docs/BUILD.md)). [Six Chunks concept references and implementation notes](docs/design/reference-images/README.md) capture the owner-selected chunky, high-contrast direction. These visual studies do not supersede product capabilities, safety or accessibility contracts.

Read [AGENTS.md](AGENTS.md), the **only live [WORK-QUEUE](docs/WORK-QUEUE.md)**, [PRODUCT](docs/PRODUCT.md), [SPEC-COVERAGE](docs/SPEC-COVERAGE.md), [ARCHITECTURE](docs/ARCHITECTURE.md), [UX](docs/UX.md), [SECURITY](docs/SECURITY.md) and [STORAGE-AND-EXTRACTION](docs/STORAGE-AND-EXTRACTION.md). [QUALITY-LEDGER](docs/QUALITY-LEDGER.md) is the evidence register, [DECISIONS](docs/DECISIONS.md) the choices register, [rTorrent matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md) the protocol detail, and [CI-POLICY](docs/CI-POLICY.md) the runner contract. The [historical October 24 critical review](docs/CRITICAL-REVIEW-2026-10-24.md) explains safety corrections, not current implementation status.

Build with JDK 17, Gradle 9.3.1 and a configured Android SDK:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

See [BUILD](docs/BUILD.md). The binary Gradle wrapper JAR is not committed; CI pins Gradle explicitly. The output is an **unsigned debug APK**, not a signed, independently accepted distribution.

## Safety and public testing

The owner specifically permits nonsecret fixtures/screenshots from one [development Seedhost](docs/SEEDHOST-REMOTE-TESTING.md) and reviewed exact-hash destructive **torrent** tests on that host. Credentials, cookies and tracker passkeys remain protected; no other accounts or uncontrolled backing-file deletion are included. Public Add fixtures come from licensed [Internet Archive/Wikimedia test sources](docs/PUBLIC-TORRENT-FIXTURES.md). Never blindly retry an uncertain mutation, equate `d.erase` with deleting payload bytes, or equate a changed remote directory setting with moving physical files. Independent filesystem privileges, journaling, recovery and hash/seeding checks are necessary for backed-file mutations.

This public repository is **not** a grant of third-party redistribution rights; license/SBOM and signed APK distribution remain open [#10](https://github.com/gamesbyian/Chunks/issues/10).
