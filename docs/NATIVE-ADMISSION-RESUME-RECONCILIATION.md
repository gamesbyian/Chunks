# Native admission and immutable resume reconciliation contract

Status: G0 / release blocking. This is an integration plan, not acceptance evidence. Last source comparison: 2026-10-31. The live PR heads, CI and source must be re-fetched before any action.

## Scope and source authorities

- [#568](https://github.com/gamesbyian/Chunks/issues/568) is the **production libtorrent payload overwrite** gate. Real installed default native writer collision tests previously changed foreign bytes in 3 of 4 cases. No admission-receipt, app-private metadata journal, research JNI or passing Android Add smoke closes that defect.
- [#812](https://github.com/gamesbyian/Chunks/pull/812) consolidates older #755 work: exclusive no-replace admission mode receipts, checked first-Start and Stop/Start fences, reviewed-existing seed provenance, magnet metadata transition, per-hash path reservations, restore uncertainty and priority mutation checks. The old mutable-receipt deletion has been removed, but this branch's legacy resume-file writing/removal must **not** ship.
- [#856](https://github.com/gamesbyian/Chunks/pull/856) replaces predictable app-private `<hash>.resume.tmp` truncation, final resume `REPLACE_EXISTING`, and `<hash>.resume` deletion with immutable exclusive-fsynced event generations and `REMOVE_INTENT` / `REMOVED` tombstones. Its GHA host/API29/API35 tests are useful source-specific evidence, but #812's admission/restore write fences are not independently preserved by merging #856 unchanged.
- [#855](https://github.com/gamesbyian/Chunks/pull/855) is a **merged, non-shipping** independent research implementation of an immutable generation chain with separate readback, bounded history and Android app-private source fixtures. It is a contract reference and adversarial test source, **never a second live resume authority**.

These implementations overlap in `LocalTorrentEngine.kt` and some engine/test integration paths. A GitHub "mergeable" status for each PR **individually** is not evidence that the union compiles, retains its behavior or is safe. The integrated branch must contain the actual union of required invariants and be retested at its own SHA.

## Non-negotiable final engine state machine

| Boundary | Required final behavior |
| --- | --- |
| Process boot | Establish an enumerable, genuinely private state root before opening JNI. A missing, unreadable, foreign, torn, duplicate or ambiguous resume entry cannot be interpreted as an empty state. Read-only inventory; never repair/clean up a guessed filename. |
| Immutable resume recovery | Parse **all** relevant generations, verify canonical infohash, exact sequence, predecessor chain, hash, byte count, record identity and legal transition. A torn later event refuses restoration of earlier bytes, even if older snapshots were valid. Retained legacy `.resume` is never overwritten or unlinked during migration. |
| Admission identity | Persist CHECKED versus explicitly REVIEWED_EXISTING provenance without allowing an old/unknown record to inherit reviewed-existing authority. An unchanged pathname, equality of hashes or receipt filename is **not** inode/descriptor ownership. |
| Fresh checked Add | Inspect declared file paths; reserve them before any native registration. Existing foreign names, ambiguous survey, competing torrent and partial native registration refuse safely. The first successful resume record belongs to the same confirmed handle/generation. |
| Reviewed-existing seed | Only an explicit affirmative review can authorize seeding existing bytes. Keep its mode and path reservation distinct from checked-download ownership. Never quietly adopt data because an old resume exists. |
| Magnet / metadata | Remain UPLOAD_MODE until actual metadata paths can be surveyed and reserved. Late metadata conflicts, paused magnets and competing checked Adds cannot open a payload write window. |
| Every Start / priority / sequential mutation | Revalidate current exact handle, generation, destination, path reservations and admission state **before** submitting an operation that could authorize libtorrent payload writes. Observation-time absence does not prevent a later pathname race: ultimate enforcement belongs to owned native disk descriptors. |
| Resume save | Use only a fresh exclusive immutable event FD, a checked fsync and parent-directory fsync; no pathname truncation, rename-overwrite or deletion. Native late alerts must be tied to the same live handle and rejected after removal intent. Full event receipt/readback must qualify success. |
| Remove keeping data | Persist and force `REMOVE_INTENT` before asynchronous `session.remove`. Retain in-session native path tombstones until `session.stop` joins old I/O. Record `REMOVED` only after the accepted request, without pretending it proves instant native disappearance. A crash at any cut point cannot auto-resurrect the old transfer. Preserve **both bytes and final directory entries** of foreign `.resume`, `.resume.tmp` and event names. |
| Same-hash re-Add | Disallow during pending/uncertain Remove or surviving native handle lifetime. After confirmed session drain and durable terminal removal, require new explicit Add review and generation. Retired state cannot be mistaken for automatically writable storage. |
| Long-term retention | Never silently delete unknown paths when the append-only store grows. A bounded journal, compaction or archive design must preserve ownership and recovery at every crash cut point; exceeding supported history safely refuses new writes with a truthful, recoverable user action. |

## Crash-cut and adversarial witness matrix

Every required case must assert the **foreign directory entry remains present** and the full foreign SHA-256 is unchanged, not merely that an API returned REFUSED.

1. Fresh clean checked Add to empty native destination, stopped-state readback, first Start and successful piece write/seed under the eventual owned disk adapter.
2. Foreign regular file, hardlink, symlink and FIFO at each old `.resume`, `.resume.tmp`, admission receipt and next immutable event name. Read refusal must be nonblocking; write/Remove may never truncate, replace or unlink.
3. Process death before/after new event create, payload write, file fsync, directory fsync and readback. A partial or invisible event never upgrades to the previous committed generation silently.
4. Death before/after `REMOVE_INTENT`, before/after native `session.remove`, before/after `REMOVED`. No zombie on restart; uncertain operations are surfaced for review and never blind-retried.
5. Checked Add vs reviewed-existing seed, competing hashes reserving identical native file names, late magnet metadata, Stop→Start, and an independent process restarting from an older resume.
6. Collision planted **after** a checked survey but **before** the actual libtorrent write, including positive-progress inode substitution and file-priority re-enablement.
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
6. **Do not lower #568** until production TLS-enabled owned libtorrent disk writer/JNI is installed and actually proves positive peer download/seed plus all strict native foreign collision SHA witnesses, with Android API29/35, real destination capabilities and physical-device evidence.

## Review guardrails

- Never treat `File.exists`, `Files.isRegularFile`, a matching string hash, or a survey/rename cycle as permission to overwrite or delete a pathname.
- Never call `Files.move(... REPLACE_EXISTING)`, `FileOutputStream(<hash>.resume.tmp)`, or pathname `delete` on unknown final entries in the production native metadata path.
- Never substitute one research implementation for shipping disk safety; compare all behaviors by source and actual CI.
- Do not publish an APK as safe for daily local torrent payload writing merely because unrelated UI, remote control, storage research or metadata-admission tests are green.
