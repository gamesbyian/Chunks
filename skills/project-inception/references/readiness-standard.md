# Per-workstream implementation readiness

Readiness means that a specific next slice is clear enough to implement without silently deciding fundamental product behavior during coding. There is no universal project-wide paperwork freeze.

| Dimension | Evidence to establish | If missing |
|---|---|---|
| Authority and baseline | Current owner decisions, project instructions and actual code/revision | Recover authoritative scope, distinguish approved from inferred |
| Actors, jobs and failures | Start-to-finish normal, cancel, error and recovery journeys, accessibility | Investigate user testimony and workflows |
| Competing and adjacent solutions | Actual behavior, feature alternatives, criticisms and counterexamples, scoped by version | Expand targeted search or label unknown |
| Reusable resources | SDK, existing code, datasets, integrations and tools with compatibility/license/trust/cost notes | Perform focused due diligence before custom implementation |
| Model completeness | Competency questions, reverse journeys and classified blank relationships | Repair evidence, aliases and schema before claiming novelty |
| Product contract | Visible states, controls, default/optional behavior, permissions and privacy | Specify affected user outcomes |
| Technical contract | Data identities, architecture, boundaries, alternatives, failure ownership | Test consequential premise, isolate uncertain slice |
| Verification economy | Observable acceptance for real user journey, focused checks and shared test setup | Select meaningful evidence before large test cycles |
| Human decisions | Confirmed vs optional vs deferred, trade-offs and recommendation | Request only authority or preference that matters |

Record each status as SUPPORTED, BOUNDED UNKNOWN, CONTRADICTED, NOT INSPECTED, INACCESSIBLE or NOT APPLICABLE with source context. A beautiful summary cannot change NOT INSPECTED into SUPPORTED.

**Verdicts for the next slice:** READY means intent, authority, user behavior, dependencies, implementation path and acceptance are clear; RESEARCH NEXT means a source might materially reverse design; DECISION NEXT requires consequential human authority/preference; SPIKE NEXT is a cheap falsifiable technical experiment; BLOCKED is a known critical dependency. Multiple slices may have different verdicts.

Prioritize unresolved unknowns by the decision they could change, credible alternative answers, cheapest discriminator, cost of rework and latest responsible decision point. Investigate broadly where the project is unfamiliar, then narrow after evidence saturation. Existing work and already settled decisions reduce the need for more questions.

**Retrofit illustration, not an approved design:** photo selection and a manually annotated outbound AYA share may be READY once URI grants and acceptance are specified. Photorealistic preservation of every unchanged mechanical-room detail might be RESEARCH NEXT pending comparative experiments. A proposed catalog size or generated image must never be treated as proof of CAD-verified equipment accuracy.
