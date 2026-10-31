# #568: Conventional torrent destination and collision contract

Status: product decision, 2026-10-31. This document supersedes the blanket assumption that every existing same-path payload is forbidden to be overwritten. It does not certify current Android implementation.

## Authorization

A user-selected download destination authorizes a torrent client to create, check, reuse and replace the **torrent-declared relative payload paths** under that destination. Hash verification controls piece correctness; it is not a filesystem ownership claim. Preexisting same-path content may be overwritten during a user-authorized download. Do not interpret the synthetic same-path overwrite diagnostics as proof of an out-of-scope write.

Do not silently authorize unrelated names, sibling files, traversal outside the selected root, symlink escapes, unintended deletion, or modification of another destination. Directory traversal and link-following escape must be rejected. Filesystem errors, revoked grants and missing media must be reported rather than treated as successful writes.

## UX

At Add, detect known same-path collisions when practical and explain the options in plain language: check/use existing data, replace mismatched pieces, or choose another folder. Keep normal clean Add frictionless. For magnets, filenames are not known until metadata arrives; defer a collision notice until then. Respect the user's previous explicit collision decision where it still applies. Existing data is not proof of completion: recheck hashes and do not trust stale fastresume state after a detectable change. Do not promise an atomic cross-process no-clobber guarantee on public shared folders.

## Tests and release classification

1. **Allowed:** same-path existing file within an explicitly selected destination is checked/reused or overwritten as authorized; clean Add, magnet, Stop/Start, recheck and resume succeed with correct piece hashes.
2. **Blocking:** torrent payload writes outside selected destination; traversal or symlink escape; corruption of an unrelated sibling path; deletion of unrelated entries; invalid path mapping; silent loss from inaccessible storage or false completion; wrong torrent/file-index routing.
3. **Android-specific acceptance:** real API29/API35 native peer write and independent readback, public Downloads storage, resume/recheck, permission and storage-volume loss, SD/SAF capabilities, physical-device validation. Each requires separate evidence.
4. The previous foreign-file tests that intentionally plant bytes at the torrent's exact expected destination should be **reclassified as characterization tests**. They should assert correct declared-path scope and user authorization, not universal byte preservation. Retain the SHA witnesses and all tests that demonstrate true out-of-scope corruption. Do not weaken a test by merely deleting its assertions.

## Implementation direction

Prefer upstream libtorrent's standard disk engine and recheck semantics for conventional destinations. A custom owned-descriptor disk backend is optional for a specifically demonstrated Android provider capability or security boundary; it is not a general release prerequisite solely because stock libtorrent overwrites an existing file with the same torrent-declared name. Keep experimental research branches non-shipping until separately validated.

## Issue management

#568 originally concerned public Downloads native backing, durability and Android access. The later universal 'never overwrite a foreign same-path file' condition is rescinded as a product requirement. Track remaining concrete failures by actual scope (out-of-root, unrelated sibling, Android access/durability, restore correctness) rather than an umbrella G0 assertion that ordinary torrent writes are inherently destructive. Do not claim completion of unresolved physical-device or Android permission tests.
