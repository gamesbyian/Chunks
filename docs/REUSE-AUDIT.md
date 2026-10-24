# Deliberate reuse from related repositories
2026-10-23. The point is to inherit working engineering practices, not their historical documentation volume or secrets.

| Origin | Adopt in Chunks | Explicitly do not transplant |
|---|---|---|
| gamesbyian/Glimpse | Kotlin/Compose native scaffold, Gradle/Android 37 CI setup, exact-SHA debug APK proof, physical-device acceptance distinct from build | Wallpaper APIs, paper-reel gimmick, its user media/assets, speculative feature matrices |
| gamesbyian/wallgen | Lean quick-vs-specialized testing, one canonical model, accessibility/touch ergonomics, source-to-output truthfulness, one current handoff instead of historical queues | Old experiment workflows, rendering tests, dozens of screenshot jobs |
| gamesbyian/UR-Recomp | AGENTS route, live work queue, explicit independent QA ledger, owner/lane mapping, partial-output recovery, lean runner governance, result-first CI logs | ROM-specific fidelity tests, five recurring agents by default, generalized vault or extra services |
| gamesbyian/general-purpose/media-organizer | Separate read-only inventory → pure proposed plan → explicit authorized exact batch → scoped executor/journal; refuse truncated inventory, changed source, case collisions, missing size, unsafe roots, ambiguous episode patterns; no credentials in PR tests | Private FTP inventory, filenames, real user media, FTP secrets, one-off deletion workflows and their environment grants |

## Migration of concepts to code
- Current Kotlin Classifier is an offline, non-mutating proof only. Extend with vetted source semantics for 1x01/SxxEyy, multi-episode ambiguity, subtitle sidecars, release tags and case-fold collisions. Don't copy private inventory or FTP specific Python implementation into this public app.
- Storage.RelocationPlanner is a **pure proposal layer**, modeled on strict preflight. It cannot authorize anything and never touches disk. Verified server-side capability and short-lived facts must precede a separate reviewed file-operations executor.
- For actual remote changes, use distinct permissions, durable operation IDs, stage/verify/commit/cleanup and explicit user confirmation. The user interface must represent uncertain state honestly.
- Prefer a single PR Android gate and manual emulator/seedbox experiments over one automatic workflow per issue. Shared GitHub runner limits apply across repositories.
- Do not connect unrelated apps, vaults, FTP/email services or GitHub secrets merely because another repository has them.

## Next importable tools, gated by need
1. A synthetic rTorrent XML-RPC fixture and sanitizing log collector, read-only by default; safe for public CI.
2. Targeted Compose screenshot/hierarchy capture based on Glimpse, opt-in only after the screen flows exist.
3. A reviewed operation-manifest schema, offline validator and per-operation journal for Companion, rather than importing any FTP executor.
4. Deterministic classification adversarial fixtures; only later optional actual media provider metadata enrichment.

This is a living decision matrix, not another competing task queue. Priorities belong to WORK-QUEUE.md and GitHub issues.