# Chunks: agent concurrency and ownership

**These are capability areas, never fixed agent jobs.** No permanent app-development roster is established by this document; inspect actual live agent/branch ownership. All agents choose from the **one** [ranked workboard](WORK-QUEUE.md), after reconciling live main, PRs, issues and active branches. No agent is presumed to be running because an old prompt mentions one. One agent may deliver multiple compatible slices; multiple agents must avoid the same source ownership.

| Expertise needed | Typical workboard issues | Coordination boundary |
|---|---|---|
| Shared environment catalog and scoped UI | #567 #8 | One immutable (environment, infohash) observation model before combined query; serialize edits to MainActivity/LocalEngineScreen and common model |
| rTorrent protocol/remote integration | #1 #4 #464 | Exact-hash mutation semantics, auth, paged list, hosted and disposable engine proof; no guessing filesystem authority |
| Android engine, backing storage and lifecycle | #5 #568 #569 | libtorrent4j, service, grants, public storage, independent copy, quotas; serialize shared engine/Gradle changes |
| Restricted filesystem/extraction safety | #2 #7 #70 #289 | Ownership graphs, journal, cancellation, failure recovery, dry-run, disposable fault injection; independent acceptance needed |
| Native UX, naming and accessibility | #8 #567 #6 | Compose journeys, suggestion provenance, 48dp/200%/TalkBack and physical handset; cannot auto-authorize backing file mutations |
| Independent acceptance and release | #9 #10 plus cross-cutting | Review exact head, inspect real screenshots and results, verify claimed environment; should not self-certify own risky executor |

**Acquisition:** follow the G0–G5 dependency gates: genuine red CI and safety first, then foundational P0 identity, native transfer, transport and QA work. A temporarily unavailable handset or host does not authorize arbitrary optional feature work. Select the highest unclaimed useful queue slice, scan issues/branches for active changes and announce precise scope via issue or PR. If parallel, agree exclusive files or a stable API seam. An owned item does not monopolize unrelated tests. Do not open rival queues.

**Exit:** build a small reviewable PR, run cheap focused tests, supply exact source SHA and evidence tier, seek independent review for filesystem/security changes, and update the owning issue plus [quality ledger](QUALITY-LEDGER.md). Qualified work may be merged; disputed/failed PRs must stay open with concrete blocker or be closed with a recorded replacement/disposition. Work ahead on a different unclaimed task while CI runs. Do not babysit or multiply workflows without need.

**Protected development testing:** named Seedhost transfer inventory is owner-approved nonsecret QA data and targeted destructive **torrent** tests are allowed. Authentication material, tracker passkeys, unrelated accounts and arbitrary remote filesystem mutations are not. Always revalidate exact identity and postconditions; no blind retries.

## Owner exception: dedicated font laboratory (2026-10-28)

Typeface development is intentionally isolated in the single persistent [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab) branch. Unlike the app workstreams above, it does **not** open small PRs or run continuous GitHub CI for each glyph. Keep it separate from app branches and app navigation/services; run local reproducible builds and audits, then submit one frozen promotion batch with full PR CI only when warranted. See [font lab operations](design/CHUNKS-FONT-LAB-OPERATIONS.md). This is a source-ownership and CI exception, not an art approval or release shortcut.

## Owner assignment: lead app agent owns open PRs and issues (2026-10-29)

The owner assigned the lead app agent ownership of **all open pull requests and issues**. That agent drives each open PR to a mergeable state (merge main in with a merge commit, fix CI properly, merge on exact-head green) or records a disposition. It also keeps each open issue's status current. This assignment does not change the font laboratory exception above: glyph drawing and font source iteration stay on `font/lab`, and promotion still arrives as one reviewed batch. The lead agent may review and merge such a promotion PR, as it did for #528, the approved ExtraBold 800, after verifying binary identity, glyph coverage and full CI.
