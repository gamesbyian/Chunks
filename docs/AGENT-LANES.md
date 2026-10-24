# Chunks development lanes and authority

These are proposed ownership lanes, not running agents. New agents must reconcile actual main, open PRs, active issue owners, CRITICAL-REVIEW-2026-10-24.md, WORK-QUEUE.md and QUALITY-LEDGER.md before writing. Nothing in a previous agent prompt authorizes unsafe production operations.

| Lane | Issues | Owns | Must not own |
|---|---|---|---|
| A: rTorrent adapter | #1 / direct control of #4 | XML/JSON RPC compatibility, auth and read-only/mutation command verification, bounded server list | Physical filesystem move, generic remote shell |
| B: filesystem safety | #2 #3 #7, assists #9 | Companion/provider topology, path proof, durability, locks, copy/rename, disposable tests | Android UI contracts, self-certifying release gates |
| C: humane UI + classifier | #6 #8, Add UI of #4 | Compose experiences, Places/Collections, transparent suggestions, accessibility and real-device tasks | Hidden filesystem mutation or alternative RPC transport |
| D: integration + release | #5 #10, acceptance coordination #9 | Gradle/source architecture, local engine, CI, durable evidence, integration | Declaring independent QA passed from build logs alone |

Independently reviewed filesystem fault tests are required even if the implementation agent wrote the tests. Shared model and Gradle edits serialize through the integration owner. A stalled agent should publish current source/tests and exact blockers through a PR, not create a second shadow spec. Never commit credentials, reusable tokens, session cookies or tracker passkeys. The owner expressly designated the development Seedhost torrent inventory as nonconfidential for fixtures and visual QA; do not extend that permission to unrelated accounts.

**Alias integration ownership:** Lane C owns local display-label UI, conservative suggestion parsing and accessibility (draft [#69](https://github.com/gamesbyian/Chunks/pull/69)); Lane D independently verifies actual Android test/visual evidence, merge compatibility and release status. Lane B separately owns any *backing-file* rename or library-copy execution under #7's journal and seeding safety gates. Do not move Chunks-only aliases into the rTorrent adapter or combine alias PR acceptance with proof of physical rename. Queue-wide recommended names and optional metadata enrichment extend #6 only through explicit privacy/consent gates.

**Next execution:** Read-only rTorrent compatibility first. The owner specifically authorized destructive tests of torrents on the named development Seedhost. Use reviewed exact-hash, single-target, audited workflows with read-back and no blind retries; this does not authorize destructive actions on arbitrary hosts or unrelated FTP files. This repository is public; code/data from the private media-organizer may inform design but must not be copied with private inventories or secrets.
