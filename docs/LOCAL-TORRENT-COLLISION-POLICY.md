# Local BitTorrent existing-file and destination policy

**Status: binding owner correction, 2026-10-31.** Decision: [D-LOCAL-COLLISIONS-20261031](DECISIONS.md). This contract supersedes the *blanket foreign-file non-clobber interpretation* formerly attached to [#568](https://github.com/gamesbyian/Chunks/issues/568). This does not claim all Android backing storage or filesystem safety has passed testing.

## User intent and normal behavior

A person who chooses or accepts a visible, authorized download directory authorizes the BitTorrent engine to create, recheck, reuse, and replace **regular payload files at the exact relative filenames declared by that torrent**. This applies to normal .torrent Adds, magnets after metadata, resumed/stopped downloads, piece-priority changes, and imports that deliberately select the destination. An existing same-name file is neither intrinsically hostile nor proof it belongs to Chunks. Its name, size or old fastresume state are also not proof of valid pieces: the engine must use torrent piece hashes to establish verified content before claiming completion or seeding.

Normal Add is decision-light: show **Save to** and an optional **Change**; do not demand an ownership ceremony for ordinary pre-existing regular files. Where known collisions could replace user data, explain unobtrusively at the actual destination selection/review context: **“Files with matching names in this folder may be checked and replaced by this download.”** A new explicit external location carries the same scoped consequence. Optionally offer a different directory; do not silently move destinations or invent ownership. Unknown magnet filenames cannot be pre-reviewed; display the scope of selected folder and apply containment after metadata arrives.

## Unchanged boundaries

- **Only torrent-declared paths beneath the exact approved root are payload-write targets.** Never write arbitrary siblings. Reject path traversal, absolute paths, symlinked payloads or parent components, unsupported aliases, special files, inaccessible roots, wrong file types, and uninspectable/unsupported layouts. A point-in-time survey is not race-proof; actual filesystem and Android-grant constraints remain important.
- **Same-path concurrent active jobs** need the existing reservation/exclusion mechanism or an explicitly designed shared-file coordination model, to avoid both engines writing conflicting pieces. An unrelated file that happens to occupy an otherwise authorized torrent path, however, may be checked and replaced.
- **Payload writing is not removal/cleanup authority.** Remove Transfer keeps bytes. Remove & Data, archive deletion, ZIP extraction, backup, export, library copy, moving/renaming backing files and changing other applications' files outside declared payload paths require their own explicit authority and non-destructive cleanup rules. This decision does not relax #809 or remote account boundaries.
- **Reality over resume hints:** detect changed, missing or inaccessible backing data, recheck actual content, and explain error/recovery honestly; don't claim old completed pieces remain valid from fastresume metadata alone. Permission loss, storage-full, process death, public Downloads / SD selection and Android lifecycle remain separate acceptance work.
- Retain the experimental owned-writer/descriptor research as optional reference. **Installing a forked custom libtorrent disk backend and demonstrating refusal to overwrite ordinary same-path regular files are not prerequisites for local BitTorrent release.**

## Targeted automated acceptance

1. Positive: real peer + piece verification + completed readback and seeding to another peer on an empty authorized path, a pre-existing matching regular file and a pre-existing mismatching regular file; include stopped/restart and file-unskip. Assert mismatched bytes become expected torrent content, and unrelated sibling bytes remain byte-exact.
2. Negative: malicious `../`, absolute/alias/overlapping metadata paths, symlinked parent and payload, directory/FIFO/device at a payload pathname, inaccessible root, foreign paths outside the chosen Place, and simultaneous conflicting native writers. Refuse before a known unsafe mutation; stress races independently and state residual limitations.
3. Android: API29 and API35 plus physical handset for public Downloads, selected SD/removable storage, permissions and recovery, hashed readback and correct destination. Do not infer a physical/OEM pass from an emulator.
4. UI: one coherent current destination, ordinary automatic Add, optional Change and honest same-name replacement explanation; no inescapable collision prompt or invisible alternate directory.

## Reclassification

The historical three-of-four native overwrite witnesses show ordinary *same-path regular-file replacement* under the former blanket ownership premise. That result alone is **no longer a G0 blocker**. Other failures (path escape, wrongly targeted output, unauthorized deletion, mismatched fastresume integrity, simultaneous native writers, permission/access loss) are independently testable, actionable bugs and must not be hidden under the old #568 label. Historical evidence is retained, not falsified or retroactively marked green.
