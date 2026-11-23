# Chunks snapshot / native-UI handoff · 2026-11-23 UTC

**Live-head instruction:** At next start re-fetch `main`, `AGENTS.md`, [work queue](WORK-QUEUE.md), open PR diffs, actual exact-SHA checks and [quality ledger](QUALITY-LEDGER.md). The hashes below are historical receipts, never future authorization.

## Accepted / merged

- [#1967](https://github.com/gamesbyian/Chunks/pull/1967): bounded ruTorrent Source reads and installed external-document selector, merged `20b37e36`. Exact-head five gates green; protected Seedhost and physical Pixel not inferred.
- [#1970](https://github.com/gamesbyian/Chunks/pull/1970): source-derived 15–17-image [visual atlas implementation plan](VISUAL-SPECIMEN-ATLAS-PLAN.md), merged `c7435062`.
- [#1971](https://github.com/gamesbyian/Chunks/pull/1971): read-only current rTorrent file-priority comparison after full exact-account observation, head `c96f64d1`, merged `a8ea9888`. Selected Android/JVM, R8, two-server, remote operation, native atlas and both installed native Add shards/verifier ultimately green. **The original native shard 1 attempt 1 was red** from real Activity focus timeout (222 pass, 1 failure, 29 skips), then unchanged-source targeted rerun passed. Keep [#1974](https://github.com/gamesbyian/Chunks/issues/1974) open: passing rerun is not root-cause repair.
- [#1972](https://github.com/gamesbyian/Chunks/pull/1972) accepted #1967's ledgers, merged `64f4e3af`. [#1975](https://github.com/gamesbyian/Chunks/pull/1975) recorded **pilot-only** screenshot provenance and gaps, merged `fd225654`.

## In progress, do not assume accepted

1. [#1976](https://github.com/gamesbyian/Chunks/pull/1976), source head `476cd036`, Chunks-only exact rTorrent account display-alias restore, deliberately **no server name, remote priorities, Start/Stop/Add or backing-file mutation**. Source changes `MainActivity`, panel, a narrow helper/test and snapshot spec. Preserve manual aliases and independent readback. At handoff, selected Android/JVM, R8, native, atlas, two-server and remote-operation checks were executing. **Inspect final actual results before merging; never erase first red attempts.**
2. Dependent [installed-alias-test branch](https://github.com/gamesbyian/Chunks/tree/test/snapshot-rtorrent-alias-installed-20261123) at `59f54df4` is staged, **untested and not a PR**. After #1976 qualifies, compare/reconcile ancestry and open separately. Its synthetic document picker explicitly checks no-write on identity mismatch, manual alias precedence, account isolation and snapshot generation invalidation. Do not claim installed acceptance yet.
3. [First-frame status bar contrast branch](https://github.com/gamesbyian/Chunks/tree/fix/native-first-launch-status-bar-contrast-20261123) at `44cd1066`, **staged/unaccepted** against [#1973](https://github.com/gamesbyian/Chunks/issues/1973), makes native dark-status-icon theme initial state explicit and adds a standard-library real-screenshot clock pixel assertion with offline negatives. The old real native `small.png` has zero bright clock pixels, other three >600; code fix needs its own actual exact-head native emulator evidence before merge. Do not trigger this visual campaign concurrently with #1976's expensive installed work without reason.
4. [Snapshot acceptance docs branch](https://github.com/gamesbyian/Chunks/tree/docs/snapshot-1971-acceptance-20261123) contains the ledger/queue update and this handoff. It is **not merged and not a PR** until exact #1976 work is safely coordinated. Avoid changing core `MainActivity` during unrelated docs work.

## Visual atlas status

[Plan](VISUAL-SPECIMEN-ATLAS-PLAN.md) and [pilot receipt](design/visual-atlas/PILOT-2026-11-23.md): 47 real native Android screenshot candidates from installed test artifacts and first-launch capture. Seventeen were selected into a local pilot ZIP/contact sheet with original PNG bytes and full SHA-256 manifest. **Not the final approved atlas:** some are isolated light-theme Compose fixtures, no honest tall scrolling screenshot, and screen/style/element inventory/coverage audit still incomplete. Do not misrepresent the 17 raw fixture screenshots as 17 app navigation screens.

## Decision rules and next actions

- Finish #1976's exact selected checks (including native build-once/shard verifier), inspect earliest red assertion and log if any; target narrow rerun only with documented first failure. Only merge an exact qualified head after confirming no code drift in main; docs-only drift must be explicit.
- Then test the staged native UI alias acceptance, and separately qualify #1973 native theme + real contrast assertion. Keep [#1515](https://github.com/gamesbyian/Chunks/issues/1515) Connections→Transfers intermittent transition and [#1974](https://github.com/gamesbyian/Chunks/issues/1974) IME focus distinct until a causal link exists.
- Merge acceptance-ledger/queue updates when truthful; do not annotate unverified remote priority **writes**, stopped definition restoration, payload copying, physical provider/TalkBack, protected hosts or signed release as complete.
- All engineering uses **GitHub connector and GitHub-hosted Actions only**. Never Desktop Commander or owner's PC. Respect CI cost and do not weaken source/account/hash/Place/Start no-replay or negative tests.
