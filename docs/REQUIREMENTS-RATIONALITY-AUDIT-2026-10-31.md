# Requirements rationality and conventional-client compatibility review

Status: proposed engineering governance; **not** permission to bypass established safety defects. Review date: 2026-10-31. Scope: gamesbyian/Chunks, P0/G0 and G1 decisions. This document is an audit and proposed refinement, not a silent override of binding owner decisions. See [DECISIONS](DECISIONS.md), [WORK-QUEUE](WORK-QUEUE.md), [LOCAL-TORRENT-COLLISION-POLICY](LOCAL-TORRENT-COLLISION-POLICY.md), [OWNED-PUBLICATION-PROTOCOL](OWNED-PUBLICATION-PROTOCOL.md), [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Purpose

Preserve correctness and the normal behaviors of BitTorrent clients and remotes without constructing custom subsystems merely to satisfy unnecessary universal proof obligations. Engineering simplicity is not a waiver of genuine data loss, credential disclosure, authorization failure, path escape, or incorrect command outcomes. User intent, actual API semantics, and reproducible impact decide the control.

## Mandatory review for new hard release gates

Every proposed release-blocking invariant must state (a) the concrete operation and target, (b) whether the user explicitly authorized replacement, deletion, or server mutation, (c) a reachable failure scenario and impact, (d) platform/library native behavior and established alternatives, (e) smallest sufficient containment and tests, (f) affected capability/build/OS/provider tiers, and (g) what positive operation can still work safely. Distinguish **observed defect**, **credible untested risk**, **capability limitation**, and **aspirational hardening**. A CI test that demonstrates expected, user-authorized operation must not be counted as a defect. Neither does a single passing emulator test prove all providers/devices.

Use four dispositions: RETAIN for evidence-backed integrity/security invariants; SIMPLIFY equivalent protection using established APIs; SCOPE to exact capabilities and tested environments; REMOVE a contradicted or unjustified demand, preserving its historical evidence. Never automatically close an issue or enable destructive code based on this document.

Prioritize ordinary successful Add/download/recheck/seed, exact-profile remote operations, destination choice and usable recovery over speculative bespoke filesystems, while retaining normal security controls and regression coverage.

## Contract review

| Area | Disposition proposed | Rational boundary and executable next step |
|---|---|---|
| Native payload collision #568 | REMOVE superseded blanket foreign regular-file non-overwrite demand. | The owner-approved [collision policy](LOCAL-TORRENT-COLLISION-POLICY.md) already authorizes conventional piece-verified reuse/replacement at exact torrent-declared paths within the chosen root. Preserve escape/symlink/special-file/concurrent writer and genuine data-integrity tests. Retire owned-writer installation as a prerequisite absent distinct failing evidence. |
| Resume/admission state #865 | RETAIN. | App-controlled internal metadata is not a user-selected payload destination. Predictable truncation, mutable cleanup and resurrection risks remain independently actionable; reconcile #812/#856 without transplanting the rejected payload rule. |
| ZIP extraction and independent library copy #809 | SIMPLIFY authorization model for **explicit user-selected writes**, but RETAIN the observed accidental cleanup and unexpected provider URI hazards. | Normal user-directed extraction can offer Skip, Replace, Rename or Ask, scoped to a clearly chosen destination with collision preview where possible; no implicit cleanup of prior unrelated files, no zip-slip, no blind truncating open of unexpected SAF URI, no assumption of atomic multi-file publication. Provider capabilities determine supported actions. First write a negative-control matrix and capability-specific implementation plan; do not enable currently unsafe production mutators until their actual failures are fixed and regression tested. |
| Generic SAF and cloud writes | SCOPE by provider capabilities, not universal promise. | Android DocumentsProvider createDocument specifies a newly allocated document ID but permits name changes; permission grants and actual behavior vary. Prefer platform contract, returned-ID validation, provider-specific feature detection, and consistent error handling. A hostile/misbehaving provider cannot be made universally trustworthy by app-created journals. Keep unsupported provider operations disabled rather than banning every provider. |
| Multi-file atomicity | SIMPLIFY. | All-or-nothing is required only if explicitly offered and supported by actual atomic primitives. A verified partial set plus precise unfinished-item reporting, retry and no speculative rollback is a valid conventional outcome. Don't misrepresent partial results as complete. |
| Background transfers/seeding #569 | SCOPE by OS lifecycle. | No universal indefinite Android 15+ background daemon guarantee. Preserve data and truthful state under service timeout/kill/reboot; resume when platform permits. Test actual lifecycle, do not bypass service policy. Inability to seed unattended forever is a limitation to describe, not proof of defective foreground torrents. |
| Physical device and provider evidence | SCOPE acceptance claims. | Track host/JVM, emulator API, installed handset, OEM and individual provider as separate tiers. A missing tier blocks the specific unproven promise, not every independently validated feature; actual unsafe changes still block relevant release. |
| rTorrent uncertain mutations | RETAIN no blind retry; SIMPLIFY recovery friction. | After timeout/401 or ambiguous response, perform read-only exact profile+hash reconciliation. Reissue only if fresh verified state and operation semantics make it safe; never replay a potentially destructive command merely because of timeout. Report genuinely unresolved outcomes. |
| Protected secrets / cross-profile targeting | RETAIN. | Never dispatch credentials to unverified host or mutate a merely same-named torrent on another account. These are meaningful authorization boundaries. |
| User-visible defaults / settings | RETAIN decision-light UX. | Downloads/Chunks Downloads when supported, optional change, remote server default, remembered login with opt-out, clear environment identity. Do not invent prerequisite prompts or silently redirect to private storage. |

## Evidence anchors and cautions

- libtorrent's documented move storage flags include `always_replace_files` as default and `fail_if_exist` as an optional policy, with a documented race window. See https://libtorrent.org/reference-Torrent_Handle.html . This is precedent for normal user-directed overwrite semantics, **not** evidence that arbitrary cleanup or path escape is authorized.
- Android's DocumentsProvider `createDocument` contract calls for a newly generated document ID but permits provider-adjusted names: https://developer.android.com/reference/android/provider/DocumentsProvider . Provider implementations and grants must still be tested, and replacement of a selected existing object must be a separate explicit operation.
- Existing #809 witnesses demonstrate specific deletion/truncation hazards. This audit does not mark #809 resolved and does not authorize reactivation of currently contained unsafe paths.
- #568's observed three-of-four same-path overwrites were reclassified in the owner-approved contract; the test observation survives but its former pass/fail interpretation does not.
- Physical handset verification remains essential before making device-wide functionality guarantees.

## Proposed integration sequence

1. Reconcile stale #568 RED prose in dated documents **by clear supersession markers**, not erasure of historical evidence. Keep #865 as actual metadata integrity blocker; stop allocating implementation capacity to the custom disk writer solely for old blanket ownership.
2. For #809, document a user-directed conflict policy independently for filesystem extraction, platform MediaStore publication, and arbitrary SAF; audit each actual operation and cleanup behavior. Keep existing containment until positive and adversarial tests justify enabling each destination tier.
3. Add a compact gate-decision field to future workboard entries: failure observed? authorization scope? affected platform/capability? positive path? minimum tests? If missing, classify as investigation rather than universal RED.
4. Record separate acceptance tiers for local foreground torrent, background seeding, public Downloads, SD/SAF, rTorrent remote and ZIP/library actions. Prevent test-tier overclaims without blocking unrelated slices.
5. Check any future bespoke JNI storage subsystem against documented platform/library behavior and explicit user-facing need before making it mandatory.
