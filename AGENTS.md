# Chunks: permanent agent operating contract

## Mission and non-negotiables
Build a colourful, genuinely usable Android-native BitTorrent client and bulletproof first-class rTorrent remote. Ordinary users must be able to choose a destination for every new transfer, reorganize in-progress data, and rename without losing seedability. Experts must retain deep server and protocol control. Categorization and filesystem location are separate, and suggestions never authorize destructive actions by themselves.

## Start here
1. Inspect current main, open PRs/issues, then [live work queue](docs/WORK-QUEUE.md). Never infer missing implementation from a historical unchecked document.
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

## Research → code → evidence
Prioritize runnable slices over documentation proliferation. Pure logic gets deterministic JVM tests; network adapters get fixture/integration tests; destructive operations require temporary test volumes, fault injection and explicit rollback/recovery evidence; Android semantics need emulator and physical-device evidence. Mock responses don't prove connectivity to a real rTorrent installation. Colour/visual approval cannot be inferred from a compiling Compose UI. Avoid one workflow per issue and don't schedule expensive CI by default.

## Project discipline
Prefer narrow PRs, no direct main edits, no force push, no conflicting parallel writes, no opaque bulk mutations. Never include a real rTorrent URL/password in code, PR, issue, screenshot or artifact. Use reusable tools only when aligned to Chunks' own contracts. Respect cross-account shared GitHub runner limits, avoid duplicate jobs, and leave one concrete next step.