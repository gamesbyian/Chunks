# Chunks technical completeness ledger

**Living capability inventory, seeded 2026-11-16.** This is a dispatch aid, not acceptance evidence. Before changing status, inspect live source and [SPEC-COVERAGE](SPEC-COVERAGE.md), [QUALITY-LEDGER](QUALITY-LEDGER.md), current PRs/CI and owner observations. [Backend-first mandate](BACKEND-FIRST-COMPLETION.md) governs priority. Never equate an open historical issue with absent implementation.

| Capability / Android core | Known status on 2026-11-16 | Highest-value remaining technical work | Evidence / contract |
| --- | --- | --- | --- |
| Authenticated rTorrent inventories / saved profiles | Implemented; real multi-profile journey passed | Preserve exact-profile identity, failure/reconnect and owner-device acceptance | WORK-QUEUE R1, #1756/#1757 |
| Remote Add, Place, Start/Stop, session Remove and Remove + data | Implemented, substantial real-primary-Seedhost proof; other server capabilities may vary | Exact-account uncertainty and variant safeguards; avoid redispatching solved blank-folder/paused-add symptoms | WORK-QUEUE R1; #1679; remote deletion contract |
| Local Add, real pieces and resume | Implemented with real-libtorrent and installed test tiers | Stopped magnet check, foreign partial files, interrupted restore and real-device validation | WORK-QUEUE R3; #5 |
| Android Share/Open With/Create / clipboard / HTTPS intake | Implemented in part; latest owner Open With failure lacks physical acceptance | Prove installed Pixel task/recreation/URI grants, second intent and source retention; do not infer device pass from fixture | #1786 and recent intake PRs |
| One Local/remote Add contract | Partial | Shared exact-client command ticket/controller, consistent source/Place/Start/status semantics and process-death uncertainty; minimal correct functional UI | Universal Add client parity design |
| Per-file download selection and priorities / Finish in Order | Partial, capabilities differ between Local and remote | Verify remote High/order at Add; harden actual file/piece postconditions, scheduling correctness | WORK-QUEUE R1/R5; #415 |
| Seeding limits / ratios / priority policies | Local: future-transfer rule snapshots, exact libtorrent upload-cap readback and once-only pause implemented; rate/ratio input safety fixed in #1818, exact-head foundation/native/R8 green. Remote: rules remain saved-only, no rTorrent enforcement | Prove Local Android lifecycle and restart/uncertain-Pause policy, then capability-gate real-server per-profile limits with independent readback. #1819 adds Local write-ahead stop evidence, acceptance pending | SMART-SEEDING-RULES; #1818 (merged); #1819 (pending) |
| Remote directory browse / creation / rename | Browse works; create capability restricted by provider; rename not shipped | Typed capability, guarded create/rename readback and failure states; never bypass containment | #1680, remote directory browser |
| Safe relocation and file operations | Partial; unsafe ZIP/library writers disabled | Capability inventory, journaled move, exact-file identity, explicit destructive approval and rollback | #2, #809 |
| Automatic media enrichment, posters and overrides | Broadly implemented and real-remote-tested | Missing identity/artwork variants, opt-out guarantees, match quality and fallback, not a wholesale rebuild | WORK-QUEUE R2; #1453 |
| Smart routing/organization | Partial/planned | Implement approved classification, client/destination defaults and predictable override rules | #6 |
| Original source Keep/Archive/Delete | Global policy implemented; real-life acceptance partial | Installed and remote lifecycle evidence, permissions and exact transactional cleanup | WORK-QUEUE R1/R3 |
| Portable snapshot export/restore | Incomplete | Versioned read-only export, integrity and intentionally paused restore with no unintended payload writes | #289 |
| Persistent settings architecture | Registry foundation exists | Typed module composition, storage ownership, atomic persistence and defaults across settings routes | SETTINGS-REGISTRY |
| Android lifecycle, network/OEM resilience and integrity | Significant proof, not complete | Endurance/Doze/VPN/partial-file/missing-file and wrong-target negatives proportionate to risk | WORK-QUEUE R3/R4 |
| Accessibility / functional interaction | Mixed acceptance | Keep necessary entrypoints/error states operable and testable now; batch elective style polish later | FEATURE-EXPERIENCE-CONTRACT |
| Release signing and distribution | Debug APK workflow exists | Finish release-candidate exact-head QA, signing/update identity/distribution | #10 |

## Explicit post-core / separate expansion scope

Other remote engines (#660), Android TV (#663), individual home-screen widgets (#689), optional companion service (#3), additional cross-media catalogs (#403), independent font variants (#347), and compatibility research (#151) remain approved or investigated expansions. Do not erase them; do not silently include them in the core Android completion denominator. Owner may promote any one of them.

## Update discipline

On each broad autonomous dispatch, verify only the affected ledger rows against the current source, issue, exact SHA and test receipts. Record implemented/integrated/real-verified/blocked separately in the PR or issue; update the table when status materially changes. Keep missing functional work more prominent than pending cosmetic refinements. Include evidence links where available; provisional table text above is a triage summary, not a completed audit.
