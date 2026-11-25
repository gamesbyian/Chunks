# Chunks evidence and release gate ledger

**Evidence index, not a status log.** The original 1,058,524-byte ledger is preserved byte-for-byte in [QUALITY-LEDGER-HISTORY-2026-11-25.md](QUALITY-LEDGER-HISTORY-2026-11-25.md), Git blob `ea43be8dc3ac990f37f6f8ee6d97fe054b8e5b36` from [main d03a781a](https://github.com/gamesbyian/Chunks/blob/d03a781a4275d90726c0c2ac7155ff942736eb07/docs/QUALITY-LEDGER.md). Search that dated archive by PR number, run ID, source SHA, test name, client or date; raw historical failures and their qualifications remain intact. Do not reinterpret historical accepted/pending statements as current status.

## Locate evidence

| Scope | Evidence entry point |
|---|---|
| Through 2026-11-25 | [Complete historical ledger](QUALITY-LEDGER-HISTORY-2026-11-25.md) |
| CI cost, exact-source APK sharing, AVD investigation | [CI optimization evidence](audits/CI-OPTIMIZATION-2026-11-25.md), [#10](https://github.com/gamesbyian/Chunks/issues/10) |
| Engineering audits and dispositions | [#687](https://github.com/gamesbyian/Chunks/issues/687), [pass 2](audits/ENGINEERING-AUDIT-2026-11-25.md) |
| Remote mutation/uncertainty and exact observation | [#2026](https://github.com/gamesbyian/Chunks/issues/2026), [#2025](https://github.com/gamesbyian/Chunks/issues/2025) |
| Add authority / physical intake | [#1844](https://github.com/gamesbyian/Chunks/issues/1844), [#1786](https://github.com/gamesbyian/Chunks/issues/1786) |
| New evidence after this archive | [Merged PRs](https://github.com/gamesbyian/Chunks/pulls?q=is%3Apr+is%3Amerged), [Actions](https://github.com/gamesbyian/Chunks/actions), owning issue linked from [WORK-QUEUE](WORK-QUEUE.md) |

## Evidence rules

Put tested SHA/tree, job and run links, actual test counts/digests, positive/negative witnesses, failures and retry scope, and missing tiers in the owning PR/issue. Do not append a receipt here for every merge. A dated standalone archive is appropriate for unique longitudinal measurements or evidence not durably held elsewhere; index it at a meaningful milestone, never copy a session transcript. Preserve expired-artifact limitations and never equate a historical green with current qualification. [Coverage matrix](SPEC-COVERAGE.md) defines gates; [release acceptance](RELEASE-ACCEPTANCE.md) defines assembled go/no-go; [queue](WORK-QUEUE.md) alone ranks next actions.
