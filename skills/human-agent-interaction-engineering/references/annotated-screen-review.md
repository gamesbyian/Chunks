# Annotated screen review: preserve, interpret and close visual feedback

**Activate:** A human supplies one or many screenshots with marks/comments/comparisons, or requests a consolidated visual UX campaign. This procedure governs collection and feedback; implementation follows the host product contract and [Experience Conformity](../../product-specification-engineering/references/experience-conformity.md). Reject for unrelated backend fixes and unrequested mandatory reviews.

**Inputs:** original image set, annotations in original words, known/unknown build and device, screen/state context, current product/design authority, existing implementation components and permission to edit. Never infer source revision or image coordinates if absent.

## R1–R6

1. **Index without loss:** register `image ID | source/build or UNKNOWN | screen/state | annotation ID | pictured target | verbatim comment`. Preserve independent instructions even if grouped for implementation.
2. **Interpret explicitly:** distinguish `observed violation of current spec`, `new binding owner direction`, `preference/proposal`, `ambiguous reference` and `runtime behavior defect`. “Move” and “disappears” may require different remedies; ask a narrowly consequential question only when needed.
3. **Trace actual ownership:** find the reusable layout token/component, navigation route, control and state source that cause each symptom. Group repeated shared causes, retain every image/mark ID as a downstream obligation, and separate distinct issues that merely look alike. Mark stale screenshot versions.
4. **Offer proportionate review:** summarize grouped changes, affected screens and surviving functional/security/accessibility constraints. Don't demand per-button approval when implementation is already delegated; unanswered options are not consent.
5. **Reconcile and implement if authorized:** update existing approved UX/spec/queue rather than inventing a second backlog. Prefer shared-component repairs before screen-by-screen patching. Preserve meaningful design reversals in the host's existing history/decisions. Validate interactions and rendering together where device setup is expensive.
6. **Close against actual evidence:** compare the *new render* and interaction to each original surviving annotation; record `annotation ID | requirement | changed component | actual image/source | fixed/partial/blocked/rejected/no-change`. A screenshot inventory, mockup or untouched code is not proof the visual requirement was fulfilled.

**Worked case:** three screens circle oversized transfer cards; one separately marks a missing Stop action. Group the three spacing observations into a shared component correction, and treat Stop as its own operation/interaction problem. Validate all four anchors, including a real Stop path, from one coordinated installed review. No three duplicate tickets and no automatic whole-app redesign.

**Negative controls:** screenshots from different builds; marks referring to hidden elements; contradictory owner notes; owner requests only a report; unapproved inspiration image mistaken for policy; beautiful before/after where the action no longer works; a simple single-label correction.

**Independent oracle and stop:** sample one original annotation and follow it through current authority, affected component and newly rendered/operable result; reverse-sample each changed shared component across affected screens. Stop when every mark has a truthful disposition or named missing evidence, not when a checklist or screenshot producer turns green.
