# Cross-engine Copy/Move Transfer: authoritative owner contract (2026-10-28)

This document supersedes earlier local↔remote Copy/Move designs involving downloaded payload bytes, file migration, CopyTransferPayload or MigrateTransfer. **Transfer means the torrent job, not its downloaded files.** The independent storage relocation, extraction and library-copy features are unaffected. The separately named, explicitly labelled **payload Copy** from a Seedhost/rTorrent transfer to the phone (merged #460/#462/#465, real-host verified 792,723,456-byte exact-hash Copy in run [40756284173](https://github.com/gamesbyian/Chunks/actions/runs/40756284173)) is a **distinct capability** that does transfer bytes over the BitTorrent peer wire; it must keep its own label, code path and evidence, and must never be presented as Copy/Move Transfer.

## User-visible behavior

**Copy Transfer to phone/server:** Acquire usable exact .torrent metainfo, or an appropriate magnet, from the source transfer. Review target engine/profile and download destination Place, metadata/trackers/private passkey consequences and duplicate conflicts. Add the torrent job on the destination and confirm by fresh target exact-infohash observation. The original job and all original files remain untouched. The destination begins its own independent download, potentially from zero. No payload bytes are copied.

**Move Transfer to phone/server:** Perform the same reviewed destination Add and independently verify its registration. Then separately confirm **session-only removal of the source torrent job**. On rTorrent use exact-target d.erase semantics; on local libtorrent remove without deleting files. Never delete, move, transmit or overwrite the downloaded source files, including partial files. No target download-completion, payload verification or seeding prerequisite: verified existence of the new job is sufficient. Explicitly warn that source data remains taking space, and that the new target may download everything again.

**Batch:** Apply these semantics to multi-select in lists/search/Collections. Show exact selection scope, source/target profiles, destination defaults/recent Places and per-item overrides, missing metadata, duplicates, estimated future **downloads** and destination capacity, per-item add and retire stages, aggregate outcome and durable partial-failure reconciliation. No "copy files", "move files and transfer", payload-transfer throughput or migrated-bytes reporting.

## Safety, implementation and acceptance

- Source and destination are separate scoped job identities, even for the same infohash. Do not infer successful Add from command acknowledgement: fresh exact-infohash readback on selected destination is required.
- Before Move retirement, refresh and verify exact source scope/hash and request a distinct, explicit source-**job-only** removal confirmation. Retire each approved job once, record evidence, and observe the result. A missing response is **uncertain**, never license to blindly retry; preserve a journal for recovery.
- If target Add fails or cannot be independently confirmed, preserve the source job untouched. If retirement fails, show the new target and original source as coexisting jobs for manual reconciliation. Never remove source payload under any circumstances from these actions.
- Use validated original torrent definitions when available, preserving identity. Handle private trackers/passkeys with consent and appropriate confidentiality; hash-only v1 magnet fallback can be insufficient for private torrents, and v2/hybrid identity or unsupported remote metadata must not silently degrade to an unusable job. Surface blockers and reviewed alternatives.
- A destination duplicate requires conflict review; do not report duplication as successful creation. Do not misrepresent already existing destination data or progress as transferred data.
- Tests: local→remote and remote→local Copy leave source job/files unchanged; both Move directions delete only source job after verified target Add and separate approval; incomplete source jobs start independent destination downloads; failed metadata, duplicate, timeout and false/uncertain Add never retire source; retirement failure retains source files and displays both jobs; mixed batches report per-item status and survive resume/reconciliation; no payload transport APIs invoked.
- UI copy: "Downloaded files will not be transferred." Move confirmation: "Remove the original torrent job after adding it to the destination? Existing downloaded files will remain on the source device/server." Expose new downloads and retained storage.

## Implementation and evidence (maintained separately)

The normative behavior and failure rules above remain binding regardless of current code status. Do not use an old implementation snapshot in this contract to decide whether a path is shipped.

**Accepted limited automated slices, as of the 2026-10-29 app-lane checkpoint:** single Copy/Move in both directions; phone → server batch Copy (#482) and Move (#502); server → phone batch Copy (#504) and Move (#506). These are independently scoped job operations; no payload transfer is implied. See [QUALITY-LEDGER](QUALITY-LEDGER.md) for commit SHAs, failing/repaired tests and actual evidence tiers, and [SPEC-COVERAGE](SPEC-COVERAGE.md) for outstanding acceptance. Installed physical-handset Move, real Seedhost batch semantics, privacy/accessibility, process-death, interruption and all-server capability compatibility remain unproven until independently tested.

The separate **Copy files to this phone** payload capability remains documented and tested on a development Seedhost but is not Copy Transfer. The [active queue](WORK-QUEUE.md) controls implementation priority.
