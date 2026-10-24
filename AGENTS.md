# Chunks: permanent agent operating contract

## Mission and non-negotiables
Build a colourful, genuinely usable Android-native BitTorrent client and bulletproof first-class rTorrent remote. Ordinary users must be able to choose a destination for every new transfer, reorganize in-progress data, and rename without losing seedability. Experts must retain deep server and protocol control. Categorization and filesystem location are separate, and suggestions never authorize destructive actions by themselves.

## Start here
1. Inspect current main, open PRs/issues, then [live work queue](docs/WORK-QUEUE.md) and [critical review](docs/CRITICAL-REVIEW-2026-10-24.md). Never infer missing implementation from a historical unchecked document.
2. Read [product](docs/PRODUCT.md) and the relevant [architecture](docs/ARCHITECTURE.md), [security](docs/SECURITY.md) or [UX](docs/UX.md) owner before editing.
3. Coordinate files and boundary changes through [agent lanes](docs/AGENT-LANES.md). A PR is evidence of a proposed change, not delivered functionality.
4. Use [CI policy](docs/CI-POLICY.md) and [quality ledger](docs/QUALITY-LEDGER.md). Cite exact test command, SHA, environment and observable result.
5. Update only one live queue and the relevant GitHub issue; preserve research as references and measured evidence in the ledger. Merge via normal checks and branch protections.

## Product invariants
- Torrent identity/hash, remote engine state, actual path, display name, file mapping, library copy, collection and classifier suggestion are distinct types/states.
- Choosing a torrent destination must be explicit and frictionless. A changed directory string **does not mean physical bytes have moved**.
- No silent overwrite, irreversible cleanup, deletion of original seeding data, or automatic renaming from an unapproved recommendation.
- Remote rTorrent XML-RPC through protected HTTPS is baseline compatibility; modern JSON-RPC requires capability-detected support. Never expose raw unauthenticated SCGI.
- The local engine is Arvid Norberg libtorrent candidate; rTorrent uses the *different* rakshasa libTorrent codebase. Never conflate their APIs.
- The companion is optional for everyday RPC; advanced filesystem actions must be refused when no safe capability exists.
- For multi-stage commands, model requested/acknowledged/verified/uncertain/failure; reconcile after timeouts before retrying a mutation.
- Secrets, session cookies and reusable tracker passkeys never go into GitHub. **Owner-authorized development Seedhost torrent inventories are explicitly nonconfidential** and may be used in project fixtures and visual QA (see scoped consent below); unrelated inventories are not. Classification local-first and opt-in for network lookups.
- No Android feature may claim guaranteed continuous background execution, unrestricted storage, or provider access without device evidence.
- Users must have accessible 48dp-class controls, alternative to gestures, large-text support, screen-reader labeling, and reduced-motion support.

## Critical review gate

The owner requested an explicit specification/plan hardening checkpoint before further feature development. Treat the revised product, architecture and critical review as current intent. After the merged #14/#17 non-mutating dashboard, **an ephemeral real rTorrent 0.9.8 read-only proof passed** in #15 ([run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245)). The next engine capability gate is compatibility with actual protected seedboxes and a materially different modern rTorrent build. Consult [the compatibility matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md). Owner-approved development Seedhost torrent mutations are permitted only through explicit, reviewed, hash-targeted tests; UI mockups are not mutation evidence. No advanced remote file mutation until independent disposable-volume journal/recovery tests. Failure to reach a real server is a documented external blocker, not permission to claim fixture success. Update decisions and the quality ledger when evidence changes, not merely when a PR proposes a capability.

## Research → code → evidence
Prioritize runnable slices over documentation proliferation. Pure logic gets deterministic JVM tests; network adapters get fixture/integration tests; destructive operations require temporary test volumes, fault injection and explicit rollback/recovery evidence; Android semantics need emulator and physical-device evidence. Mock responses don't prove connectivity to a real rTorrent installation. Colour/visual approval cannot be inferred from a compiling Compose UI. Avoid one workflow per issue and don't schedule expensive CI by default.

## Project discipline
Prefer narrow PRs, no direct main edits, no force push, no conflicting parallel writes, no opaque bulk mutations. Never include a real rTorrent URL/password in code, PR, issue, screenshot or artifact. Use reusable tools only when aligned to Chunks' own contracts. Respect cross-account shared GitHub runner limits, avoid duplicate jobs, and leave one concrete next step.
## Protected hosted-server tests

The repository contains a **manual-only** `seedhost-readonly-probe.yml`, added in #20, for a credentialed read-only version query on a Seedhost host. Do not put the owner's server URL or secret values into code, PR text or CI logs. The protected Python HTTP-RPC version/auth smoke **passed** at [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095); the Kotlin adapter, full server listing and handset access require separate observed evidence. See [SEEDHOST-REMOTE-TESTING.md](docs/SEEDHOST-REMOTE-TESTING.md) for accepted secret names and the difference between inferred URL and authenticated proof.

## Development seedbox fixture and visual-evidence consent (owner clarification 2026-10-24)

The owner explicitly designated the connected Seedhost rTorrent instance and its currently running torrent inventory as **development/test data, not private or confidential**. Accordingly Chunks may commit real torrent names, normal media paths, file layouts, nonsecret tracker metadata, statuses and representative sanitized fixture snapshots; and may capture/review full UI screenshot atlases and QA reports, drawing on Glimpse and ROYGBIV visual acceptance practices. This supersedes the earlier blanket prohibition on publishing *torrent inventories* for this designated development instance only. It is **not** permission to commit login credentials, session cookies, authentication headers, embedded announce passkeys or other reusable tokens, and is not a blanket reclassification of other personal servers or accounts. Redact secrets even if embedded in nominally public URLs. Mutation safety, disposable test gates, backup/recovery and owner approvals remain in force independently of disclosure consent.

## Owner-authorized destructive testing (2026-10-24)

The owner expressly authorized **destructive torrent operations on the designated Seedhost development instance for testing**. This permits targeted test Add, stop/start, priority changes, rename, relocate, remove and cleanup of torrents/data when implemented with explicit target selection, dry-run where meaningful, before/after inventory evidence, verification and recovery paths. This authorization supersedes previous assumptions that all owner Seedhost data must be treated as production or immutable. It **does not** waive data-integrity engineering requirements, permit uncontrolled bulk deletion, or authorize mutation of another server, account or filesystem outside this test scope. Develop and exercise write support incrementally with exact-hash allowlisting, bounded batches, negative cases and logs. Credentials and embedded tracker secrets remain protected even though the designated test inventory is nonconfidential.

## Public test corpus for Add

For future Add scenarios use [the public fixture collector](docs/PUBLIC-TORRENT-FIXTURES.md) with licensed Internet Archive .torrent metainfo and derived exact-infohash magnets. Wikimedia Commons provides licensed direct files and dump assets, NOT presumed torrents. Gate destructive live tests behind exact infohash/destination, snapshot, verification and documented owner approval. Never source random search torrents or embed reusable tracker passkeys.

## Controlled real-server public Add acceptance

The [manual public Add smoke](.github/workflows/seedhost-public-add-smoke.yml) defaults to preview without contacting Seedhost. To mutate the owner-authorized development instance, select `apply=true`, supply the exact source-derived v1 infohash as a confirmation input and a chosen absolute directory. The manual-only workflow issues at most one stopped `load.raw_verbose` request for the public fixture, checks preexisting identity and verifies hash/path/stopped state. Never auto-retry a missing acknowledgement or silently remove ambiguous results. PR tests are offline and receive no secrets. App UI Add remains gated by its own real-server/device evidence.

## Current code baseline and pending visual evidence

As of the 2026-10-24 source recovery, the app has a native two-step stopped Add flow (#44), exact-byte v1 `.torrent` import (#40/#43), Basic/Digest client auth (#38), confirmed Start/Stop transfer controls (#49), and an accepted disposable real rTorrent Add/lifecycle experiment (#46). These do not establish a successful owner Seedhost Add or physical Android UI task. Separate native screenshot/hierarchy atlas work (#45/#48) must yield actual captured PNG/semantics artifacts before visual QA is marked passed. The native Add screen is not the full product. Consult WORK-QUEUE and QUALITY-LEDGER for the latest proof rather than treating these references as perpetual status.
