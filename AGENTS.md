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
- Secrets and torrent inventories never go into GitHub issues, CI logs, public fixtures or telemetry. Classification local-first and opt-in for network lookups.
- No Android feature may claim guaranteed continuous background execution, unrestricted storage, or provider access without device evidence.
- Users must have accessible 48dp-class controls, alternative to gestures, large-text support, screen-reader labeling, and reduced-motion support.

## Critical review gate

The owner requested an explicit specification/plan hardening checkpoint before further feature development. Treat the revised product, architecture and critical review as current intent. After the merged #14/#17 non-mutating dashboard, **an ephemeral real rTorrent 0.9.8 read-only proof passed** in #15 ([run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245)). The next engine capability gate is compatibility with actual protected seedboxes and a materially different modern rTorrent build. Consult [the compatibility matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md). UI prototyping does not authorize remote writes. No advanced remote file mutation until independent disposable-volume journal/recovery tests. Failure to reach a real server is a documented external blocker, not permission to claim fixture success. Update decisions and the quality ledger when evidence changes, not merely when a PR proposes a capability.

## Research → code → evidence
Prioritize runnable slices over documentation proliferation. Pure logic gets deterministic JVM tests; network adapters get fixture/integration tests; destructive operations require temporary test volumes, fault injection and explicit rollback/recovery evidence; Android semantics need emulator and physical-device evidence. Mock responses don't prove connectivity to a real rTorrent installation. Colour/visual approval cannot be inferred from a compiling Compose UI. Avoid one workflow per issue and don't schedule expensive CI by default.

## Project discipline
Prefer narrow PRs, no direct main edits, no force push, no conflicting parallel writes, no opaque bulk mutations. Never include a real rTorrent URL/password in code, PR, issue, screenshot or artifact. Use reusable tools only when aligned to Chunks' own contracts. Respect cross-account shared GitHub runner limits, avoid duplicate jobs, and leave one concrete next step.
## Protected hosted-server tests

The repository contains a **manual-only** `seedhost-readonly-probe.yml`, added in #20, for a credentialed read-only version query on a Seedhost host. Do not put the owner's server URL or secret values into code, PR text or CI logs. The live smoke has **not** run. See [SEEDHOST-REMOTE-TESTING.md](docs/SEEDHOST-REMOTE-TESTING.md) for accepted secret names and the difference between inferred URL and authenticated proof.
