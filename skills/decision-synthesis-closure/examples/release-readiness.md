# Worked example: release-readiness audit and owner correction

**Status:** fictionalized teaching case, not an assertion about any currently shipping project. This example illustrates the mechanics of an audit and its reconciliation; it is not a security exemption or a release qualification.

## Raw source findings

An audit of a file-transfer application finds:

| Source ID | Observation | Evidence level |
|---|---|---|
| F01 | Cross-provider Move may reach a copied destination before original deletion is committed | source + targeted test |
| F02 | A test for Move fails during UI teardown despite byte-exact copies | test result; root cause unverified |
| F03 | Work queue duplicates F01 as “cleanup after copy” | planned work, not new proof |
| F04 | Destination conflict behavior is unspecified | spec gap |
| F05 | Export button has a minor visual alignment issue | observed UX issue |
| F06 | Provider access can expire between copy and deletion | platform contract + unverified app recovery behavior |

The agent must not conclude that F02 means copied bytes were corrupted. It must not reduce F01 and F06 to “delete data is inherently unsafe.” It must not assume F05 is a blocker unless the release's actual acceptance criteria say so.

## Consolidation and distinction

- **Capability C1: complete Move with recoverable interruption.** F01, F03 and F06 inform one connected engineering objective. The implementation must define how it handles destination success, source deletion, expired permission and retry; separate independent acceptance checks remain attached.
- **Capability C2: deterministically establish an accepted Move journey.** F02 is a verification problem with an unknown UI teardown cause. It may relate to C1, but it is not established as the same defect.
- **Decision D-04: destination conflicts.** F04 is a genuine product choice only if prior specifications and platform conventions do not settle overwrite/rename/skip policy.
- **Nonblocking polish P-05:** F05 goes into ordinary work unless release criteria elevate it.

## Human-facing cards (only where judgment is required)

**D-04: Destination conflict policy.** “We can't finish specifying what Move should do when a file with the same name already exists, because no conflict rule has been chosen.” Ask whether to replace, keep both or prompt, with data-loss and friction consequences.

**D-07: Challenge to ‘safe deletion’ framing.** Only ask if investigation shows a genuine unresolved choice about acceptable risk. “We can't finalize the Move recovery rules because the current safety requirement doesn't distinguish deleting the source the user deliberately moved from deleting unrelated data.” The question asks which distinction the owner intends, without implying that every deletion is a defect.

Don't ask the owner to determine why a teardown test failed. Investigate it and report separately. Don't ask whether byte-exact copying is acceptable without evidence that owner policy is unsettled.

## Interpreting the response

Owner replies to D-07: “Make sure our definition of safely is sane.”

**Incorrect:** mark deletion safety waived; close C1 and ship.

**Correct:** classify as a **challenge to the risk model**. Reconstruct the actual operation and source identity, permissions, user intent, idempotence, provider effects and failure states. Distinguish authorized deletion of the source after successful Move from accidental deletion of unrelated files, ambiguous outcomes and irreversible retries. Revise an overbroad prevention rule if evidence supports that; retain independently justified safety obligations. Ask a narrow follow-up only if consequential product tradeoffs survive investigation.

## Reconciliation record

```yaml
decision_id: D-07
response: "Make sure our definition of safely is sane."
speech_act: premise_challenge
interpretation: "Re-examine deletion safety scope; no blanket waiver"
evidence_rechecked: [F01, F03, F06]
linked_capability: C1
requirements_preserved:
  - "Do not delete a source unless the user-authorized Move reaches its defined deletion stage"
  - "Do not delete unrelated data"
  - "Handle interrupted/uncertain provider operations without assuming success"
actions_proposed:
  - "Replace vague blanket deletion prohibition with operation-specific invariants"
  - "Group interrupted Move and permission expiry recovery work where they share code"
status:
  decision: interpreted
  documentation: pending
  implementation: pending
  verification: pending
  release: not_established
```

## Evaluation controls

Given the same evidence, a qualified agent should detect the independent teardown evidence gap, consolidate true duplicates without losing F06, avoid presenting the cosmetic issue as a necessary release obstacle, and correctly classify the owner's statement as a challenge requiring investigation. A weak agent either enumerates six questions, hides separate safety requirements while consolidating, or uses vague owner feedback as a waiver.

**Stop rule:** once remaining unknowns cannot alter owner choices, return to authorized implementation and independently relevant checks. The agent has not proved readiness merely by producing clean cards.
