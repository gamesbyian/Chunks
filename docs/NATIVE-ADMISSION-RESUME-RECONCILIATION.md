# Native admission and immutable resume reconciliation contract

**Owner-policy update (2026-10-31):** this reconciliation is now scoped to **app-private resume/admission metadata durability, no unrelated metadata-entry overwrite/unlink and restoration correctness** ([#865](https://github.com/gamesbyian/Chunks/issues/865)). The owner's [D-LOCAL-COLLISIONS-20261031](DECISIONS.md) authorizes normal libtorrent reuse/replacement of same-name regular **payload** files inside the selected destination. Any older instructions here to require custom JNI payload owner receipts or strict refusal of ordinary existing payload files are superseded, not release gates. Do not discard the independent metadata safety tests.

Status: G0 / release blocking. This is an integration plan, not acceptance evidence. Last source comparison: 2026-10-31. The live PR heads, CI and source must be re-fetched before any action.

## Scope and source authorities

- [#568](https://github.com/gamesbyian/Chunks/issues/568) is **closed as superseded**: conventional same-path regular payload replacement in an authorized folder is permitted by [D-LOCAL-COLLISIONS-20261031](DECISIONS.md). The historical 3/4 witness is still accurate but cannot be used to reopen a blanket owner-writer gate. [#865](https://github.com/gamesbyian/Chunks/issues/865) tracks actual private resume/admission metadata overwrite, unlink, and restoration correctness; [#5](https://github.com/gamesbyian/Chunks/issues/5) tracks Android/physical backing acceptance.
- [#812](https://github.com/gamesbyian/Chunks/pull/812) consolidates older #755 work: exclusive no-replace admission mode receipts, checked first-Start and Stop/Start fences, reviewed-existing seed provenance, magnet metadata transition, per-hash path reservations, restore uncertainty and priority mutation checks. The old mutable-receipt deletion has been removed, but this branch's legacy resume-file writing/removal must **not** ship.
- [#856](https://github.com/gamesbyian/Chunks/pull/856) replaces predictable app-private `<hash>.resume.tmp` truncation, final resume `REPLACE_EXISTING`, and `<hash>.resume` deletion with immutable exclusive-fsynced event generations and `REMOVE_INTENT` / `REMOVED` tombstones. Its GHA host/API29/API35 tests are useful source-specific evidence, but #812's admission/restore write fences are not independently preserved by merging #856 unchanged.
- [#855](https://github.com/gamesbyian/Chunks/pull/855) is a **merged, non-shipping** independent research implementation of an immutable generation chain with separate readback, bounded history and Android app-private source fixtures. It is a contract reference and adversarial test source, **never a second live resume authority**.

These implementations overlap in `LocalTorrentEngine.kt` and some engine/test integration paths. A GitHub "mergeable" status for each PR **individually** is not evidence that the union compiles, retains its behavior or is safe. The integrated branch must contain the actual union of required invariants and be retested at its own SHA.

## Non-negotiable final engine state machine

| Boundary | Required final behavior |
| --- | --- |
| Process boot | Establish an enumerable, genuinely private state root before opening JNI. A missing, unreadable, foreign, torn, duplicate or ambiguous resume entry cannot be interpreted as an empty state. Read-only inventory; never repair/clean up a guessed filename. |
| Immutable resume recovery | Parse **all** relevant generations, verify canonical infohash, exact sequence, predecessor chain, hash, byte count, record identity and legal transition. A torn later event refuses restoration of earlier bytes, even if older snapshots were valid. Retained legacy `.resume` is never overwritten or unlinked during migration. |
| Admission identity | Persist CHECKED versus explicitly REVIEWED_EXISTING modes only to the extent needed for legitimate mode-specific actions and restoration; do not treat the mode as a blanket restriction against ordinary same-path regular payload data. An unchanged pathname or matching hash is no proof of metadata-file ownership, nor proof of verified torrent pieces. |
| Fresh checked Add | Inspect declared torrent paths and reserve the intended writer scope before native registration. **Existing regular files at exact declared payload paths are permitted** for verified reuse/replacement. Reject ambiguous/escaping/symlinked/special paths, competing torrent writers and uncertain partial registration safely. The first successful private resume record belongs to the same confirmed handle/generation. |
| Reviewed-existing seed | Retain deliberate existing-data/seed-mode support, but do not require a separate ownership ceremony for a normal download with matching regular filenames. **Only actual piece-hash verification authorizes reporting completion or seeding.** Existing bytes, old resume hints and mode labels alone do not. |
| Magnet / metadata | Remain UPLOAD_MODE until actual metadata paths can be surveyed and reserved. Late metadata conflicts, paused magnets and competing checked Adds cannot open a payload write window. |
| Every Start / priority / sequential mutation | Revalidate current handle, declared-path containment, selected destination, nonregular/symlink targets and competing writer reservations before native work. Existing regular files within the selected torrent path are allowed. The survey is a bounded observation, **not a promise of atomic containment against an adversarial concurrent path swap**; a custom owned-disk JNI backend is not required solely to refuse these same-name regular files. Resume/admission metadata writes independently require exclusive descriptor-bound commits. |
| Resume save | Use only a fresh exclusive immutable event FD, a checked fsync and parent-directory fsync; no pathname truncation, rename-overwrite or deletion. Native late alerts must be tied to the same live handle and rejected after removal intent. Full event receipt/readback must qualify success. |
| Remove keeping data | Persist and force `REMOVE_INTENT` before asynchronous `session.remove`. Retain in-session native path tombstones until `session.stop` joins old I/O. Record `REMOVED` only after the accepted request, without pretending it proves instant native disappearance. A crash at any cut point cannot auto-resurrect the old transfer. Preserve **both bytes and final directory entries** of foreign `.resume`, `.resume.tmp` and event names. |
| Same-hash re-Add | Disallow during pending/uncertain Remove or surviving native handle lifetime. After confirmed session drain and durable terminal removal, require new explicit Add review and generation. Retired state cannot be mistaken for automatically writable storage. |
| Long-term retention | Never silently delete unknown paths when the append-only store grows. A bounded journal, compaction or archive design must preserve ownership and recovery at every crash cut point; exceeding supported history safely refuses new writes with a truthful, recoverable user action. |

## Crash-cut and adversarial witness matrix

**For app-private resume/admission metadata**, every adversarial fixture must assert the foreign directory entry remains present and its full SHA-256 is unchanged, not merely that an API returned REFUSED. **For torrent payloads**, existing regular files at selected declared paths may change to verified expected content; unrelated sibling/out-of-root paths must remain unchanged.

1. Fresh checked Add to an empty or same-path-occupied authorized destination, stopped-state readback, first Start, real peer write/recheck/readback and seeding with the standard supported libtorrent disk implementation.
2. Foreign regular file, hardlink, symlink and FIFO at each old `.resume`, `.resume.tmp`, admission receipt and next immutable event name. Read refusal must be nonblocking; write/Remove may never truncate, replace or unlink.
3. Process death before/after new event create, payload write, file fsync, directory fsync and readback. A partial or invisible event never upgrades to the previous committed generation silently.
4. Death before/after `REMOVE_INTENT`, before/after native `session.remove`, before/after `REMOVED`. No zombie on restart; uncertain operations are surfaced for review and never blind-retried.
5. Checked Add vs reviewed-existing seed, competing hashes reserving identical native file names, late magnet metadata, Stop→Start, and an independent process restarting from an older resume.
6. Existing regular payload inserted **after** a survey (before actual libtorrent write, including positive-progress replacement and file-priority re-enablement) must follow ordinary authorized piece-check/replacement; separate adversarial symlink, special-file, traversal and unrelated sibling/out-of-root mutations must be refused or preserve unrelated bytes. Explicitly state the residual filesystem race limitation.
7. Genuine native Remove → close → re-open → inventory absence → explicitly reviewed same-hash re-Add, including a preserved foreign legacy resume entry.
8. Full disk, directory fsync error, unlistable directory, missing/duplicate/oversized/tampered chain records and concurrent saves: fail closed with no destructive pathname cleanup.
9. Repeat at Android 10/API29 and Android 15/API35; include actual native JNI/peer tests, real public Downloads and removable/SAF capability-specific refusal/positive cases where supported, plus physical handset/OEM tests.
10. Long-running repeated Saves: measure bytes/operation, journal growth, performance and a crash-safe retention plan. A refusal due to cap must never corrupt existing in-flight transfers.

Test fixtures must mint their own directories and never touch user downloads. Host mock or JNI linking is supporting evidence only; **installed**, non-skipped assertions are required at Android gates.

## Integration execution order

1. Freeze or explicitly coordinate both native-edit branches; fetch **new** #812/#856 heads and compare all altered production/test paths with current main. Preserve merged #855 as a nonactivated reference.
2. Build one synthetic candidate with a **single** `LocalTorrentEngine` state machine: #812 admission/provenance/Start/priority/reservation protections plus #856 immutable persistence, removal and restore. Review the complete resulting source rather than merely a union of green PR descriptions.
3. Install a matrix of precise JVM and real Android transition tests for the combined paths, including actual Remove→restart→re-Add and existing foreign SHA/entry witnesses. Resolve every compile and source-level failure, rerun **exact-head** foundation, R8, API29, API35, lifecycle, disposable rTorrent, native post-Add and host/Android adversarial tests.
4. Review and prove journal bounds/compaction or a safe hard refusal with explicit UX. A separate durable head/descriptor ownership protocol and crash recovery are mandatory before a general user-visible, unlimited resume feature.
5. Independently review **that same final SHA** and merge with an expected-head guard. Keep prior branch history and evidence, close superseded PRs with cross-links, and update WORK-QUEUE / QUALITY-LEDGER.
6. **Do not reopen superseded #568** for same-path regular payload replacement. Close #865 only after the tested integrated metadata solution, Android/JVM acceptance and independent source review; track real Android physical storage, SD/permissions and seeding acceptance under #5/#569 with exact evidence tiers.

## Review guardrails

- No pathname survey or matching hash can authorize **metadata-file** overwrite/deletion; torrent payload writes are separately permitted only at exact declared paths inside an explicitly selected root.
- Never call `Files.move(... REPLACE_EXISTING)`, `FileOutputStream(<hash>.resume.tmp)`, or pathname `delete` on unknown final entries in the production native metadata path.
- Never substitute non-shipping owned-writer research for evidence of supported standard native torrent functionality; compare actual JNI/Android behavior and maintain private metadata integrity independently.
- Do not publish an APK as ready for everyday local downloading solely because UI, remote-control or metadata tests are green; require actual native peer, hash, resume, public Downloads and platform-specific evidence for the capabilities being advertised.
