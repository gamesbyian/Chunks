# Research user needs and prior art before designing mobile behavior

This is an **editorial synthesis** of source-backed requirements interrogation, adaptive layout, readable async states, accessibility, RTL and device testing. It does not claim to be a published user study or a universal design rule.

## Reconstruct the job first

Keep four things distinct: **user goal**, **actual current behavior and workaround**, **possible visible UI/interaction**, **underlying engineering mechanism**. “Put a button here” could signal discoverability, needless navigation, wrong defaults, missing feedback or a feature gap. The user's proposed solution is evidence about a desired outcome, not automatically the right component.

## Choose research sources that can change the decision

- First-party product truth: owner decisions, tests, app code, user reports, current screen hierarchy, accessible interactions and real supported screen sizes.
- Original user testimony: praise, criticism, friction, workarounds, nonusers, neutral comments and preference for a competing product. Preserve language, product version, author/thread identity and sampling limitations. Search native phrases for everyday interaction rather than translating every keyword literally.
- Prior art: inspect a few relevant mature implementations, when needed, for **default controls, visible metadata, action location, feedback, error/empty/loading states, cancellation, recovery and secondary detail**. Screenshot descriptions do not prove runtime behavior or user preference.
- Platform requirements: actual Compose/Flutter/React Native/iOS/Android APIs, adaptive window size, large text, TalkBack/VoiceOver, keyboard and screen-reader focus, RTL, color-independent state, permissions and reduced motion.
- Disconfirming cases: novice vs expert, small viewport, 200% font, poor connectivity, partial server failure, inaccessible gesture, unwanted destructive operation and abandonment.

No survey sample or prevalence rate may be invented. A product popularity count is not a usability preference metric.

## Five questions worth asking

1. Which **job** is frequent or costly enough to deserve the primary surface? Are rare destructive tasks accessible but appropriately secondary?
2. Which facts must be **recognizable at a glance** (identity, name, environment, progress, trustworthy status) and which belong in an expansion?
3. Which taps, dialogs, confirmations, context switches and waits are **avoidable friction** without weakening meaningful consent?
4. Can users distinguish **loading, genuinely empty, stale, offline, error, pending mutation and recovery**? Are drafts preserved after failure?
5. Can every intended user complete the task with actual viewport, 200% text, keyboard/assistive navigation, RTL and reduced motion?

Map `segment -> goal -> journey -> interaction/control -> underlying state/authorization -> visible feedback -> recovery` if relationships are unclear. Reverse-walk the map from a user complaint and from a competitor's working flow. A missing link may be a retrieval omission, alias, schema blind spot, known unlinked behavior, deliberate exclusion or candidate genuine UX gap.

## Decision and stopping rule

Compare plausible interactions with explicit evidence, action cost, reachability, status semantics and failure recovery. Prefer conventional components where they meet hard constraints, and name a counterexample that would reverse the design. Define an installed-user-journey acceptance test, not only a mock-up.

Stop researching when the consequential user jobs and viable alternatives are covered, the important unknowns are bounded, and further sources are unlikely to change the choice. Reopen on observed user feedback or contradictory tests. Do not turn ordinary design changes into a permanent requirements committee.
