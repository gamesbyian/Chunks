# Worked example: “Hey, are there some choices that need making?”

**Teaching fixture:** Hypothetical project states and choices. These are not assertions about any live repository.

## Trigger

The owner unexpectedly asks the agent, during ordinary development: “Hey, are there some choices that need making?”

**Treat this as a real request to inspect available current evidence**, rather than asking for an issue number or merely listing open tickets. If the project is known from the active conversation, reuse it. Review the current project's owner decisions, code/docs, work queue, active PRs and relevant research as feasible; identify snapshot and known omissions. If the agent is not connected to the repository, disclose that and use only available information, never claim a complete live audit.

## Candidate inventory from sources

| ID | Category | Human-readable choice | Supporting distinction |
|---|---|---|---|
| D01 | Needed soon | File conflict default: ask, rename, replace or skip? | Unresolved product authority; release path depends on it |
| D02 | Upcoming | Should a long-running transfer show a persistent notification? | Can be decided before UX freeze; platform constraints still apply |
| D03 | Optional | Should advanced sorting be per-view or global? | Agent could choose conventional default; owner might value alternatives |
| D04 | Reconsider with evidence | Compact rows were previously approved; new testing suggests legibility problems at narrow widths | Explicit existing decision + new credible counterevidence |
| D05 | Agent-owned / informational | CI job duplicates compilation | Engineering workflow improvement, doesn't need a human question |
| D06 | Not a current choice | Previously resolved storage path preference | Don't repeatedly present settled choices without reversal evidence |

## Appropriate response

“On the inspected snapshot, I found **one decision needed soon, one upcoming, one optional and one prior decision worth reconsidering**. Another issue is routine engineering work; it needs no decision from you. The list is limited to the files and runs I could inspect.”

Show D01–D04 in compact, grouped cards, each with grounded recommendations, other real options, consequences, a comment field and skip/defer. D04 must disclose the earlier owner's approved choice and the reason for reopening it, rather than proposing it as a fresh blank question.

The agent can list D05 in a small “I'll handle these within existing authority” line if the owner wants visibility. D06 should not be re-asked. If the user asked for a **comprehensive** choice inventory, list all materially distinct items and coverage limits; don't arbitrarily cap at four.

## Example response interpretation

Human selects an alternate default for D03, writes on D04 “don't change this yet, but inspect the narrow-screen case,” and skips D01/D02.

- **D03:** provisional preference (not automatic instruction to edit code).
- **D04:** request for further investigation; original compact-row direction remains in force until superseded.
- **D01/D02:** unanswered, not waived, approved or deferred. If D01 genuinely blocks work, explain the specific remaining dependency, continue independent work where permitted, and ask again only when consequential.
- **D05:** agent can address under existing permissions and relevant test evidence, without treating silence as approval.

Return a short receipt linking each answered ID to the interpretation and next action. If the user explicitly authorizes specification updates, reconcile into existing project authorities and report actual changes; otherwise stop at the decision briefing.

## Additional control cases

1. **No material choices:** “I found no current questions needing your decision in the sources checked; there are routine tasks I can handle. The latest hardware test result wasn't accessible.” Do not invent choices to make the form interesting.
2. **Urgent irreversible action:** present exact target/scope and require explicit approval; a casual survey selection isn't authorization.
3. **Stale work queue:** reconcile with code and merged PRs rather than presenting closed items as new decisions.
4. **Director requests 25 choices:** use an organized complete inventory with partial submission, without false ranking or forced selections.
5. **New evidence reverses premise:** offer a reconsideration card showing what was previously decided, the new evidence and how that changes the viable choice.
6. **Human requests merely “show the choices”:** don't mutate specifications on the basis of unanswered or preview-only cards.
