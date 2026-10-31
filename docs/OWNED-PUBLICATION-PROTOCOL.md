# Chunks: owned output publication and non-destructive recovery protocol

Status: **G0 design contract, not release acceptance** (2026-10-31). This document defines the next positive storage path for [#809](https://github.com/gamesbyian/Chunks/issues/809) and its requirements. It does not turn on blocked ZIP extraction/library copy or authorize cleanup. Separate native libtorrent backing files remain under [#568](https://github.com/gamesbyian/Chunks/issues/568). See [storage and extraction](STORAGE-AND-EXTRACTION.md), [work queue](WORK-QUEUE.md) and [quality ledger](QUALITY-LEDGER.md).

## 1. Invariant and trust boundary

Before any output file is opened for mutation, Chunks must hold **creation-specific evidence that the provider minted a new object, that the object is in the chosen scope, that the opening descriptor still targets that object, and that the request cannot replace an already existing file**. A missing name, a path survey, an Android picker callback, a matching hash, a journal intent or the absence of a previous matching URI is **insufficient**, separately or in combination. After mutation Chunks must durably flush owned bytes and independently reread the exact scoped object before describing the operation as saved. Nothing ever deletes a named node as a rollback unless creation-specific identity and destructive authorization have independently been proved; the default is to preserve ambiguous output and alert the user.

This is a **provider-specific** capability. A generic `DocumentsProvider.createDocument` can choose a different name or return an existing document URI. A document URI alone does not establish exclusive creation, and opening it with `w`, `wt`, `rw` or `rwt` may overwrite unrelated content. No amount of after-the-fact readback repairs that authorization failure.

## 2. Supported capability classes

| Destination | Permitted current operation | Positive promotion requirements |
|---|---|---|
| Android app-private cache | New read-only report/metainfo share via exclusive `CREATE_NEW`, a pinned descriptor and bounded readback | Verify full chooser grant, receiver access and safe cache retention; no claim that recipient saved |
| ZIP input review | Bounded anonymous O_TMPFILE/memfd stage and read-only verifier | Always close descriptor; never reopen for write or delete name/age matches. Unsupported vendor kernel refuses review |
| Platform `MediaStore.Downloads` on the primary volume | **Research only:** insert one fresh pending record, verify exact owner/row identity, write through its new descriptor, fsync, reopen/readback, unpend and reread | [#833](https://github.com/gamesbyian/Chunks/pull/833) actual Android 29/35 tests, independent negative controls, process-restart receipt, OEM acceptance and explicit UI semantics |
| Platform MediaStore on removable volumes | No write capability assumed | Discover actual mounted volume, owner, pending and descriptor semantics without modifying foreign files; independently qualify each API/volume class |
| Arbitrary SAF document tree, cloud provider, removable SD through `DocumentsProvider` | Read-only listing/inspection; write/copy/extract **blocked** | A provable exclusive-create **and** descriptor identity/no-replace contract, provider-specific regression controls, durable publication and recovery. Otherwise offer an owned local export/share, not an unsafe fallback |
| Native torrent data backing | This protocol confers **zero** additional authority | Native writer's own pinned directory/write ownership, collision/crash proof and [#568](https://github.com/gamesbyian/Chunks/issues/568) acceptance |

Never silently switch a selected destination. A fallback from unsupported SAF to platform Downloads or an owned read-only share is a **new, explicit user choice** with a truthful destination label.

## 3. Single platform Downloads publication

A successful `MediaStore.Downloads.insert` from the actual platform provider on API29+ is **a request for a fresh row**, not evidence for arbitrary provider APIs. The proposed minimal exact-row receipt contains: operation UUID; requested volume and collection; numerical row ID and exact returned collection URI; device/user storage volume identity; app package owner; freshly randomized filename and expected relative directory; MIME type; pending state; length and SHA-256; and durable journal phase. Require the returned URI to be the exact numeric ID beneath the expected Downloads collection; reject arbitrary authorities, nested paths, userinfo, query fragments, and alias URIs.

Before write, query the **exact returned row**, requiring the reported ID, `OWNER_PACKAGE_NAME`, name, relative path, MIME type, `IS_PENDING=1`, and initial size zero. Unknown owner, missing fields, rewritten name or inconsistent URI are refusals, not reasons to “repair” the row. Open the row's read/write file descriptor once, reject nonregular or nonempty objects, stream bounded bytes through that same descriptor, and `fsync` before close. Open a fresh read descriptor to independently compare exact byte length and SHA-256. Publish only that exact URI by changing `IS_PENDING` from 1 to 0, require an exactly-one-row acknowledgement, then query identity/state and independently reread the bytes again. Return a `VerifiedPublished` receipt **only after** those postconditions. Repeated names create distinct randomly named rows; never open an existing path to resolve collisions.

`IS_PENDING` is a platform visibility guard, **not** a proof that a generic SAF provider made a new inode or an atomic multi-file transaction. The pending row can expire and a provider can deny all of the required capabilities. In both cases: preserve previously existing user data, refuse without fallback writes, retain any known journal evidence and report uncertainty accurately.

## 4. Crash-safe journal, consent and states

Reserve a random operation ID and fsync an app-private **intent record** before any provider insert. This establishes the user's original explicit action, never file ownership. After a successful insert, append and fsync an exact returned URI/row scope receipt **before opening the writable descriptor**. Store expected size/digest from the original immutable input, and record completion using an append-only bounded journal with `CREATE_NEW` and retained descriptor; recovery must ignore torn trailing records and must not delete or truncate a journal by pathname.

| Last durable state | On process restart | User-visible meaning |
|---|---|---|
| No record | No provider enumeration and no cleanup | Nothing reliable is known |
| `INTENT`, no row ID | Unknown whether the insert happened; provider may eventually expire an orphaned pending row | “Interrupted before we could confirm a destination” |
| `ROW_RECEIPT` with exact URI | Read-only query of owner, scope, pending and bytes; **no automatic write/delete** | “An unfinished export may exist” |
| `BYTES_SYNCED` | Independently reopen/read; verify exact scope/owner/size/digest again | “Verified private pending output” only if every check passes |
| `PUBLISH_REQUESTED` without verified result | Read exact URI and state; published only if owner, scope, digest, size and `IS_PENDING=0` match | “Publication uncertain; review” otherwise |
| `VERIFIED_PUBLISHED` | Repeat optional read-only verification before reusing a receipt | “Saved by Chunks to the verified Downloads location” |

Recovery may display an **explicit** action to complete a known, still-pending, byte-verified owned row, with fresh user authorization and revalidation immediately before the mutation. It may never retry an uncertain create, make a second copy to an inferred pathname, re-open a stale alias, or delete files to enforce a tidy appearance. Journal logs and unowned prior partials remain for review.

A process crash **after provider insert but before the row-ID journal commit** is inherently ambiguous. Chunks does not search by random filename and delete a guessed orphan. The platform's pending expiration policy may reclaim such entries; no app-level destructive sweep is authorized.

### Current Android-owned-row evidence and journal implementation (2026-10-31)

The dormant single-row platform MediaStore implementation [#833](https://github.com/gamesbyian/Chunks/pull/833) has now **merged** after **actual API29 and API35 emulator runs** [41166697656](https://github.com/gamesbyian/Chunks/actions/runs/41166697656), three executed non-skipped cases each, plus exact-head Android foundation/R8/API35 tests. This is *verified emulator research*, not the app's active Save action or physical-user-storage acceptance. An initial API29/API35 refusal was isolated to the provider's indexed `SIZE` field lagging while `IS_PENDING=1`; the accepted implementation never relaxes numeric returned URI, `OWNER_PACKAGE_NAME`, MIME, requested relative path, exact display name or pending-state checks. Before publication it uses freshly reopened FD `fstat` size and bounded SHA-256 readback; **after** unpending it requires the provider's indexed size and reopened physical byte readback to agree. No path-based rollback is authorized.

[Draft #843](https://github.com/gamesbyian/Chunks/pull/843) connects the dormant writer to an app-private, `CREATE_NEW` + `NOFOLLOW_LINKS` fsynced journal and exact operation UUID, recording `INTENT` before insert, `ROW` before writable descriptor access, `SYNCED` after independent physical verification, `PUBLISH` before the exact one-row unpend update, and `VERIFIED` after positive publication readback. The read-only assessor rejects partial/forged/reordered records; an intention or row receipt never authorizes output writes, retries, adoption, deletion or cleanup. On restart, a provably matching, physically re-read published row can be recognized even if the final journal success record was interrupted. A genuinely matching pending row is reported separately; it is **not** silently resumed. The current Android/JVM acceptance matrix includes corrupted bytes and interrupted pending rows but exact-head CI is **pending**. No production UI calls this journal or publisher yet.

**Outstanding positive release gates:** concurrent first-create durability; crash at every journal boundary (including insert-before-row-receipt); malicious/rename collision and pending namespace squatting; app-private journal torn writes and changed-object negative cases; OEM/FUSE and removable volume tests; user-controlled explicit Save location with privacy warnings, accessibility, background process handling and honest completed/uncertain messaging; and separate multi-file ZIP/library transaction semantics. Android platform MediaProvider patch [87beb397](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/87beb3978b6a5182bcf9f6515a55f44d7a5c40b) documents pending namespace race concerns, which is why exact pre/post owner/name/URI checks cannot be treated as ornamental. Keep generic SAF publication disabled under #809 and native writer #568 independently RED.

## 5. ZIP extraction and independent library copy

A multi-file publication is not atomically committed merely because each new MediaStore row is individually safe. These operations need an explicit **transaction manifest** with immutable source archive/payload identity, reviewed member path mapping, per-member unique row receipts, expected length/CRC/SHA-256, storage-space budget, cancellation state, publication order and a user-visible partial outcome.

The ZIP verifier must reject zip-slip, cross-entry collisions, unsafe links/device entries, archive bombs, abnormal compression ratios and interrupted streams before any destination write. Review does not confer filesystem authority. For a positive supported destination, independently stream each member to a newly owned pending row and verify all members before making them public. Publish one row at a time and record each exact postpublication receipt. If publication stops halfway, **show a partial verified set and unfinished members**; the platform cannot offer a true atomic directory-swap transaction for an arbitrary set of rows. Never claim all-or-nothing or silently delete an already published file. If the product requires atomic folder publication, use only a provider-specific transaction primitive that has actually been proven, or refuse that mode and offer a single owned container document.

Library copying has the same destination proof, plus a **source** boundary: the local torrent source may be renamed, replaced or updated while copying. Obtain a source-specific stable snapshot/FD and verify its exact identity and contents; never assume a reported transfer path is immutable, and never remove or alter the source torrent payload. Do not promote source rename or automatic post-extraction archive deletion from this protocol.

## 6. Failure, accessibility and scope semantics

The user should see **where** output will go, **which environment** owns it, whether it is merely prepared for sharing, pending verification, genuinely saved in a known location, incomplete, or unsupported. A casual user may skip advanced options, but an unreviewed setting cannot cause an unintended overwrite or a hidden destination switch. On unsupported SAF providers, present a constructive alternative: “This location cannot guarantee safe new files. Choose Downloads or share a prepared copy.” Explain that copies are independent of seeding and that no originals are deleted. Offer a detailed receipt inspector for technical users.

A privacy-locked transfer or server profile must not leak its filenames, paths, hashes or private tracker passkeys through exports or journal UX without the same environment identity and user consent checks as the source action. Use bounded status details; never log raw private URIs, tokens or server secrets.

## 7. Required independent acceptance matrix

The minimum matrix before any positive feature switches on includes fresh positive writes, exact identity and digest readback after process restart, cancelled streams, broken/short/zero-progress provider reads, fsync failure, duplicate names, two concurrent creations, wrong returned URI/authority, missing or changed owner, old/nonempty provider row, malicious existing-URI substitution, hardlinks/symlinks, revoked grants, low space, incomplete source streams, repeated Start/Stop/priority changes where relevant, torn journals, a crash at **every** journal state boundary, partial multi-file publication, and post-publication readback loss.

Run **actual** Android 29 and 35 against disposable platform-owned providers and a deliberately adversarial `DocumentsProvider`, with negative controls proving rejection. Add independent API29/35 physical-device storage proof for intended real-world volumes. For SD and cloud providers, publish a provider/version-specific capability matrix rather than treating emulator success as universal. A green workflow confirms only the cases it genuinely ran at its **exact source head**. Keep #809 open until both positive destination and recovery contracts are proven; keep #568 separate until native foreign-file nonclobber accepts.

## 8. Implementation order and ownership

1. Preserve merged [#811](https://github.com/gamesbyian/Chunks/pull/811), [#824](https://github.com/gamesbyian/Chunks/pull/824), [#829](https://github.com/gamesbyian/Chunks/pull/829), [#820](https://github.com/gamesbyian/Chunks/pull/820) and [#826](https://github.com/gamesbyian/Chunks/pull/826) safeguards.
2. Qualify the independent, non-shipping single-row MediaStore writer [#833](https://github.com/gamesbyian/Chunks/pull/833) on Android 29/35; repair real failures, no weaker fallback.
3. Add exclusive fsynced journal integration and restart/torn-record negative controls. A research state model alone is not production mutation authority.
4. Introduce read-only capability/receipt surfaces to the shared Compose owner, then an explicit single-document `Save to Downloads` path when independently accepted. Keep arbitrary SAF unsupported.
5. Build multi-file pending-row manifest and staged source identity contract for ZIP extraction and independent library copy; prove nonclobber and honestly partial transactions. Expand volumes only when each is tested.
6. Obtain independent review and exact-head acceptance for the combined implementation. Keep native libtorrent work isolated to its engine owner.

