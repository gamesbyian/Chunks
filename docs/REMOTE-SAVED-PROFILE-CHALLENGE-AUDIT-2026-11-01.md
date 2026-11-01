# Recovered remote evidence and saved-profile security audit (2026-11-01)

## Recovered accepted protected installed Android remote mutation receipts (2026-11-01 audit)

**Historical accepted UI and adapter evidence, not current-head qualification:**
[Protected installed Android Add UI 40652034320](https://github.com/gamesbyian/Chunks/actions/runs/40652034320)
on 2026-10-28 at `46b00dd7dd6adda5393929d4101a3b52316f1832`
passed its actual installed app Add flow against the owner's designated
development Seedhost: a unique synthetic magnet at a verified Place,
app-reported Verified Add+Start, independent exact-hash started/directory
readback, exact Stop and session-only Erase, restoring unrelated inventory.
Fixed terminal log stage was `inventory-restored`; no draft failure.
[Protected installed Android adapter mutation 40581190487](https://github.com/gamesbyian/Chunks/actions/runs/40581190487)
on 2026-10-27 at `fc8fb8430b9587a2665c315eadf0991d049bb8c8`
separately confirmed synthetic exact Add, active Start, Stop and
session-only Erase with unrelated inventory preserved. Its terminal
sanitized marker explicitly disclaims app UI or peer bytes.

These accepted real protected-host emulator receipts supersede stale
2026-10-27 claims that the hosted installed Add UI has not succeeded.
They prove neither current main nor physical Android/OEM, outside-UI
file-priority integration, timeout/401 mutation recovery, conventional
public-peer receipt or per-infohash received+hash-verified byte growth.
The separate later protected current-main
[read-only UI 41266326823](https://github.com/gamesbyian/Chunks/actions/runs/41266326823)
covered credentialed Connect, nonempty queue, Refresh and Add Place
preview without write. No extra protected mutation was dispatched
during this audit. The exact hash/byte gate remains open #4.

## 2026-11-01 accepted oversized read-only remote paging

[#892](https://github.com/gamesbyian/Chunks/pull/892) merged as
`6ffec6b19013a120955c4fed40367f0acb53242a` from exact reviewed
head `dfcff21aeff512b3223384b5fd6c993a3d3a6360`. All five CI
checks succeeded: [foundation 41267740532](https://github.com/gamesbyian/Chunks/actions/runs/41267740532)
(255 reports, 1,171 executed JVM cases, 30 skipped, zero failed, debug
APK assembled), [disposable real rTorrent 41267740585](https://github.com/gamesbyian/Chunks/actions/runs/41267740585)
(legacy and modern separate engine jobs), [R8 41267740577](https://github.com/gamesbyian/Chunks/actions/runs/41267740577),
and [actual native Add review 41267740565](https://github.com/gamesbyian/Chunks/actions/runs/41267740565).

The adapter now recursively bisects only oversized **read-only**
`system.multicall` exact-hash getter pages after the large-inventory
fallback, preserves row order and existing identity/member fences, and
refuses unsplittable rows, bad XML, timeouts, server faults and changing
view membership. Three localhost HTTP tests cover the positive and
two refusal modes. This does not prove real thousands-row Seedhost queues,
live byte growth, credentials, Android mutation or any write replay.

## 2026-11-01 saved combined-server reauthentication: source-only candidate

In the existing `MainActivity` combined-saved-server callback, a newly
constructed `RTorrentRemote(profile.credentials)` directly read the
`main` view without requiring the challenge-accepted credential proof
added to normal Connect in PR #898. Saved credentials could have expired,
been reassigned or point to a public-read endpoint without the UI knowing.
The staged `fix/remote-saved-profile-challenge-revalidation-20261101`
candidate calls `verifiedSavedProfileMainInventory` from that production
callback and requires fresh read-only version/main-view+HTTP challenge
evidence for supplied credentials. Test cases cover public invented
credentials, accepted Basic, expired Basic, intentional anonymous access and
numbered-instance separation without fallback to the primary mount.
It does not add mutation or login persistence. Final #908 source-head automated CI passed five groups, with independent 0F source review. Protected-host and physical-Android account acceptance remain open; archived rows remain stale if reauthentication fails.

