# Feature experience contract

**Binding delivery method for any user-visible change.** [PRODUCT](PRODUCT.md) and [UX](UX.md) define the behavior and intended experience; feature-specific approved designs govern their details. This contract defines how agents turn those requirements into implementation and evidence. It adds no second backlog, fixed agent role, automatic emulator job or new owner-approval step.

## Decide applicability before implementation

- **No visible effect:** Pure engine/protocol/test/internal work records `experience impact: none` and its reasoning in the PR/issue. Normal functional/security evidence still applies. Do not generate decorative screenshots.
- **Visible state, action or navigation affected:** Deliver the smallest coherent **experience slice** alongside its engine or adapter change. Reuse existing canonical screens, components, terms, state owners and entry points; don't invent a second Add, Details, list, dialog or settings store merely to expose a new capability.
- **Dedicated design overhaul:** Follow the approved reference and its migration contract, inspect all affected screens and interactions together, then conduct broader visual comparison. Normal feature PRs must not opportunistically redesign unrelated UI.

## Feature experience slice (short PR/issue record)

For a UI-impacting change, state the following in concise, directly testable language; reference existing spec sections rather than duplicating them:

1. **Entry and placement:** user's task, entry point, owning screen/component, scopes (device, named server, All), and what remains above the fold. Prefer a contextual control over a new top-level destination.
2. **Presentation:** information priority, compactness, shared typography/colour/spacing/action pattern, relevant approved mockup/reference and exact states (empty, checking, loading, paused, unavailable, stale, error, uncertain). Do not interpret an unapproved screenshot as a pixel-perfect requirement.
3. **Interaction:** primary action, default, required/optional decisions, touch and keyboard/TalkBack feedback, cancel/return, persistence and restored state. Do not add a redundant confirmation for ordinary reversible actions. Preserve exact environment/account/Place/identity and destructive authorization.
4. **Evidence:** behavioral and data-integrity assertions; semantic/keyboard/accessibility checks for affected controls; one or more *representative actual-render* screenshots when presentation changed. State how the image was inspected against the owner-approved visual/interaction contract. A screenshot's existence, filename or a green capture job is **not** visual approval.

Keep this record proportionate: one compact paragraph or short table for a minor status change; a linked design contract for an entire screen. Existing feature specs may already supply all four items.

## Definition of done and evidence tiers

- **Functional status** and **experience status** are separately reported as `accepted / acceptance-pending / failed / not-applicable`. A backend change can be functionally accepted while an explicitly tracked UI integration remains pending. Don't imply the full user feature is complete until both applicable dimensions are accepted.
- **Experience acceptance** requires correct screen ownership and hierarchy, discoverable/reachable controls, truthful and useful state/feedback, consistent shared components, and available task completion/return without UI dead ends. For presentation changes, inspect representative rendered evidence; compare with approved references and note mismatches. An agent review may be provisional; owner screenshot feedback remains authoritative when supplied.
- **Visual evidence** may be an existing native atlas screenshot, installed capture or Robolectric rendering. Record what was *visually inspected*, versus *captured but not evaluated*, and the source/viewport. No unapproved golden-image baselines or brittle coordinate/copy assertions. A visual defect is a product defect even if semantic tests pass.
- **Primary design target:** owner Pixel 6 Pro, as defined by [UX](UX.md). Preserve accessibility, adaptive layout and existing supported smaller-viewport behavior; the 320dp/alternate-size matrix is generally secondary, not a blanket P0 release gate. A specifically demonstrated obstruction on the primary handset is P0. Never weaken 48dp-class targets, TalkBack, IME or state accuracy in the name of density.
- **Efficient validation:** Use cheap local semantic/layout tests and an existing representative capture for incremental UI changes. Review screenshots at meaningful milestones, not every commit; use installed UI / real-peer gates only for the code paths affected under [CI-POLICY](CI-POLICY.md). Don't block an internal feature on owner review of unrelated visual polish.

## Coordinated owner screenshot pass

Keep a distinct, traceable **experience-debt** list for existing approved deviations, without manufacturing a second work queue. Preserve screenshots/annotations, affected screen/component and desired behavior in the owning issue/coverage row. Group shared causes (padding, action-grid, row density, footer, typography, state copy) into coherent fixes rather than independently patching every screenshot. Do not pause ordinary engineering while waiting for the owner's eventual comprehensive UI review.

**PR handoff:** Report affected screen(s), reused component(s), interaction and recovery, functional evidence, visually inspected evidence or why not applicable, remaining experience debt and the authoritative spec. A green atlas inventory means images were produced, not that their contents were approved.
