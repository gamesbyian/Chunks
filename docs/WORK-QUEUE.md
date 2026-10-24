# Chunks current implementation queue
**Live state as of 2026-10-24.** Reconcile main, open PRs, issues and the [quality ledger](QUALITY-LEDGER.md) before claiming anything shipped. Historical research notes are not open tickets.

## Actual baseline
- [x] Existing public repo identified; initial content only one README.
- [x] Product and engineering invariants determined; issue lanes opened (#1–#10).
- [x] Foundation PR #11 merged at `6dba63f`; Android source build and unit tests green on exact PR SHA `201b53f` ([run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588)).
- [ ] Verified connection to an actual rTorrent instance.
- [ ] Any write/move/rename capability accepted against disposable fixtures.
- [ ] Working local BitTorrent engine and handset-tested background operation.
- [ ] Physical Android interaction and accessibility sign-off.

## Priority / dependencies
| Order | Issue | Required next evidence |
|---|---|---|
| P0-A | #1 | Read-only XML-RPC shell merged, but live HTTPS seedbox verification, method/version matrix and fault matrix still needed |
| P0-B | #2 then #3 | Failure-safe remote file operations, path mapping, companion proof, crash journal |
| P0-C | #4 | Add with explicit Place; files/priorities and verified state |
| P0-D | #5 | Local engine integration and Android OS lifecycle proof |
| P1-A | #6 | Classifier, categories vs Places, explainable rule decisions |
| P1-B | #7 | Seed-preserving renaming/library copies and bulk workflow |
| P1-C | #8 | Coherent Compose product journeys, independent accessibility/device review |
| P1-D | #9 | Fault injection, large-scale remote stress and security acceptance |
| P1-E | #10 | Signed release, privacy, dependencies and distribution contract |

## Work acceptance protocol
For each issue: link changed source and tests; record source SHA, environment, exact commands, observations and unresolved limitations. Close only upon real acceptance, not because a document claims it. Current main is the authority for implemented code.

Parallelize adapter, classification and UI only after their interfaces stabilize. One agent owns shared Gradle and domain contracts at a time. No speculative weekly CI or runner-heavy screenshot sweeps by default.