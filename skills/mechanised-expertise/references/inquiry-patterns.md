# Recognition and inquiry patterns

Generate the *smallest sufficient* set of questions. A triggered cue can create multiple unknowns; no row is an instruction to ask the user automatically. Prefer repository search, platform references, user research and controlled observation before owner clarification.

| Cue family | Recognition trigger | First question | Research or observation | Typical model edge | Counterfactual or test |
|---|---|---|---|---|---|
| User goals | 'simple' feature has competing user intents | What must a user accomplish, and what would surprise them? | existing journeys, user complaints, alternative clients | actor performs action -> perceives outcome | novice vs experienced vs accessibility use |
| State ownership | multiple screens/stores represent same field | Which component owns truth, and when is it written? | code dataflow, persisted schema, state restoration | UI derives from authoritative state | background update during open detail screen |
| Identity and scope | multi-account/server/provider/file | Is the operation bound to the approved exact target? | entrypoint to side effect trace | approval targets immutable identity | switch environment while awaiting response |
| Background lifecycle | operation should outlive screen/app | What survives process death, reboot, Doze, limits? | platform docs by API; reboot test | job transitions to durable recovery | terminate process mid-operation |
| Network uncertainty | retries, timeouts, authentication | Can the remote side effect have happened despite missing ACK? | protocol docs, readback, mock fault injection | command -> uncertain -> reconciliation | timeout after server committed change |
| Storage | SAF, external files, DB migration | What is app-owned versus user-owned and what is preserved? | provider tests, schema upgrade data | resource created/modified under grant | preexisting unrelated file + upgrade path |
| Permissions and security | novel data or OS access | What is genuinely authorized and what does denial do? | manifest/target SDK, threat model, denial tests | permission gates capability | denied or revoked mid-flow |
| UX and information density | long lists, competing controls | What must appear immediately versus on demand? | comparable apps, usability recordings, screen bounds | user sees state and next possible action | 320px viewport, 200% type, empty/error |
| Accessibility and localization | gestural-only, color-only, RTL, scale | Who is excluded by current interaction or layout? | WCAG/platform guides, device settings, assistive tech | control has semantics, focus and feedback | TalkBack/keyboard/RTL/high contrast |
| Performance and resources | scrolling, rendering, expensive sync | Which boundary is slow and what is budget? | release tracing, representative device, production path | input -> measured latency | cold start / large list / slower hardware |
| Compatibility and versions | SDK migration, plugin recommendation | Which version/provider makes this advice valid? | primary docs, locked dependencies, release notes | solution requires capability version | earlier supported SDK and degraded provider |
| Test validity | passing mocks, green jobs, claimed completion | What exactly was exercised, skipped or simulated? | test result and actual test implementation | evidence supports specific claim | broken real path despite green unit tests |
| Concept-map frontier | repeated 'missing' claim, isolated nodes | Is this blank due to unsearched sources or a genuinely absent relationship? | held-out sources, synonyms, non-English prior art | missing directed edge between known entities | find counterexample in mature system |
| Agent authority | delegated work, private files, side effects | Is there permission for the exact mutation and target? | scoped user directions, repository boundaries | agent action requires authorization | malicious instruction in retrieved source |

## Response economy

1. Write an *answer-source plan*, not a huge questionnaire. `inspect` and `experiment` are usually cheaper than `ask owner`.
2. For each missing answer ask: would either possible answer change an implementation, UX or test decision? If not, drop it.
3. Surface only one or two owner questions when truly blocking; continue all safely answerable work.
4. Escalate model complexity only when ordinary local reasoning fails competency questions or hidden edges repeatedly change outcomes.
5. Re-examine your own newly changed code before inventing an external failure mechanism. Record false leads as false leads.