# Open PR reconciliation, pass 3 (11 November 2026 UTC)

Status: evidence pointer, not product authority or an agent assignment. Supersedes the "recovery map" in [pass 2](OPEN-PR-RECONCILIATION-2026-11-11-PASS2.md) now that [#1644](https://github.com/gamesbyian/Chunks/pull/1644) is merged (`2812eaf`).

## Disposition of the 16 PRs open after #1644

| PR | Disposition | Where the work lives |
| --- | --- | --- |
| #1646 media identification | merged cleanly into #1647 | #1647 |
| #1546 frozen exact-job alias | production and unit tests ported; two stale test edits dropped for main's canonical versions | #1647 |
| #1611 saved-profile Add authentication | ported; `MainActivity.kt` conflict hand-resolved (Disconnect abandons the pending resume) | #1647 |
| #1637 first-install control hiding | ported; main's newer visual tests retained | #1647 |
| #1587 earlier Welcome | production change identical to #1637 | #1647 via #1637 |
| #1535 / #1545 / #1548 / #1551 test migrations and assemblies | `ConnectionsScreenPresentationTest` was the only test file absent from main; ported | #1647 |
| #1521, #1528, #1613, #1616, #1618, #1619, #1639 | no unique test files on main; #1594, #1534, #1565, #1566 already ported by #1644, navigation/Welcome landed through #1640/#1644 | #1640, #1644 |

Not carried: #1619/#1639's archive qualification document (history only). Nothing was compared at line level for #1613/#1616/#1618; they are stacked parents of #1619 and are treated as superseded by the #1640 integration.

## Evidence tier

Content comparison and a hand-resolved merge only. No exact-head CI result is recorded here; see the PR checks and `tools/pr_acceptance_report.py` output for the head that is actually merged. Emulator and disposable-server evidence only; no physical-device claim.

## Open follow-ups unchanged by this pass

Listed in pass 2: stale picker/handoff result overwrite, `selectAuthenticatedAddTarget()` torrent/magnet drop, Forget-while-connected and stale-list indicator on the removed `ServerScreen`, startup auto-reconnect narrowing Transfers, files-panel reset on refresh, redundant owner re-checks, required-check ruleset on `main`, Preview APK publication only after exact-head acceptance. Remote media file evidence remains the inventory host's responsibility by design.
