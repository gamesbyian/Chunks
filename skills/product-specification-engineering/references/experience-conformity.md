# Experience conformity: bind user-facing behavior, interaction and presentation

**Activate:** An accepted feature or change creates, moves, removes or changes user-visible controls, status, navigation, layout or feedback. Apply when specifying the experience and again when implementing/accepting it. The host project's current product authority overrides an agent's stylistic preferences.

**Reject or abbreviate:** A genuinely internal parser, protocol, storage, CI or test-only change with no visible delta records `experience impact: none` and a reason in its existing work item; no screenshot obligation. A settled typo or local visual fix reuses the current contract and a focused check. Do not demand a new dossier, owner sign-off per PR, or a redesign for either case.

**Inputs:** current owner/product decisions, existing UI/screen/component code, accepted design and accessibility policy, actual shipping journey and state owners, supported device/viewport, available rendered evidence and validation budget. Mark conflicting/unavailable inputs explicitly. Current screenshots show what exists; exploratory mockups are not authoritative designs unless adopted.

## E1–E7 execution

1. **Classify and anchor:** name the task, actor and real surface; choose `NONE | VISIBLE_STATE | INTERACTION | PRESENTATION | REDESIGN`. Capture exact approved spec/revision and surviving functional, accessibility, privacy, destructive-action and identity requirements. Pure internals stop at a reasoned NONE.
2. **Find the canonical owner:** trace job → discoverable entry → existing screen/component → action/state owner → feedback/exit. Inspect neighboring screens, component reuse, defaults and current design tokens before inventing another route, settings panel or card. Resolve authority conflicts rather than creating a second copy of functionality.
3. **Compile the smallest experience contract into existing PR/issue/spec:** record `entry/placement`, `information priority/visual hierarchy`, `common controls/tokens`, `trigger/default/cancel/return`, `state feedback`, `accessibility/viewport`, and `reference/evidence`. Include empty/loading/checking/partial/success/uncertain/error/restored variants only where reachable. Reuse established sections; a small change may be a few lines.
4. **Implement the experience with the feature:** expose truthful authoritative state, preserve stable object/account/permission identity across interactions, reuse canonical components, and retain established layout/density/navigation grammar. If the engine precedes its UI for dependency reasons, mark experience work OPEN and avoid declaring the whole feature complete. Normal feature work must not opportunistically redesign unrelated screens.
5. **Derive independent, noninterchangeable acceptance:**
   - **Functional:** actual operation/data/provider postcondition with risk-relevant negative controls.
   - **Interaction:** user discovers and operates the intended control through the shipping interface, with correct target, semantics, focus, feedback, navigation and recovery.
   - **Presentation conformity:** inspect the *actual rendered image* in representative changed states against approved hierarchy, density, text, grouping and visual references. Record image identity, device/viewport, source revision, observed deviations and reviewer; a screenshot filename or atlas producer only proves evidence exists.
   - **Visual regression:** pixel comparison only against a host-approved stable baseline. Never treat historical filenames, mockups or arbitrary coordinates as normative.
   No renderer available means PRESENTATION UNVERIFIED, not PASS. Human perceptual approval and physical-device acceptance are separate when required by the host.
6. **Validate economically:** use cheap edit-local checks for changed semantics/state, one shared installed/rendered pass for compatible UI changes, early focused gates for risky architecture or destructive flows, and the host's supported primary device with risk-selected secondary variants. See [Validation Economics](../../android-app-development/references/validation-economics.md). Do not automatically add an emulator job for documentation or internal work.
7. **Reverse-trace and report:** for each added control ask which current user outcome and canonical destination justify it; for each visible requirement find the implementing screen and qualifying oracle. Reconcile obsolete tests/atlases through [change-aware test contracts](../../references/change-aware-test-contracts.md). Report independently `FUNCTIONAL VERIFIED`, `INTERACTION VERIFIED`, `PRESENTATION REVIEWED`, `EXPERIENCE ACCEPTED`, `PENDING` or `N/A`. Update affected host docs, not a second backlog.

## Minimal example

`Job: start/stop a transfer from the compact list.`
`Entry: canonical transfer row in All/device/server scopes, with existing Details route.`
`Presentation: common small row action beside progress, not a new top-level screen; stopped/starting/running/uncertain rendered distinctly.`
`Interaction: exact transfer/account ID, pending then verified feedback; keyboard/TalkBack, list scroll retained after return.`
`Evidence: real target state readback; installed row action and focus; actual row image checked against approved compact-list design for normal and error states.`

## Discriminating controls

- **Positive:** a new priority control belongs in an existing compact list. Locate the approved action slot and existing visual vocabulary, then prove remote/local target, reachability and rendered conformity.
- **Negative:** an internal piece-hash correction changes no visible state. Functional proof is sufficient; no presentation ritual.
- **Negative:** one approved label replacement is cheap and reversible. No new design research/survey.
- **Negative:** an unapproved aesthetic mockup conflicts with binding layout. Treat it as a proposal, not authority.
- **Negative:** a removed wizard's screenshot filename remains a CI requirement. Preserve the underlying identity/accessibility invariant but update evidence to the current approved flow, rather than reinstating the obsolete wizard.
- **Counterexample:** precise warning copy/contrast/placement is explicitly approved and still binding. Maintain its test; visual requirements are not inherently brittle.

**Independent oracle:** another reviewer, using the resulting code and actual render, can trace one current owner outcome to the specific visible entry/interaction/presentation and its evidence, and reverse-trace a new control to legitimate authority. Correct paperwork alone is insufficient.

**Stop:** every changed experience claim has current authority, an actual implementation/evidence disposition, and remaining gaps or owner choices identified. Don't expand into full-project visual sweeps or permanently assigned UX roles. A later concentrated owner-annotated redesign remains compatible with correct feature-level implementation.
