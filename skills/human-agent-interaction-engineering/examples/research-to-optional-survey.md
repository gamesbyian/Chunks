# Worked example: prior-art and user-criticism research → optional design survey

**Teaching fixture:** Invented products, observations and sample responses. It is *not* an empirical torrent-app market survey. A real agent must cite original evidence and qualify each constituency claim.

## User request and result

The owner requests investigation of prior art for file-transfer or torrent-client features and of user preferences/criticisms. The agent delivers a substantive report with exact examples, versions, original-language feedback, contradictions and sampling caveats. It notices several *choice-rich* dimensions and offers a follow-up: “I can turn the research into an optional feature-design survey. You could select, comment on, or skip any of 25 items; choices would be treated as provisional until you say otherwise.”

**Do not replace the research report with the form.** Do not manufacture 25 choices when research only substantiates six; use the actual choice inventory. If asked explicitly to generate the survey, generate it without an extra opt-in hurdle.

## Candidate synthesis (illustrative subset of 25)

| ID | Feature | Research-supported alternative categories (hypothetical) | Distinct user contexts to investigate | Existing decision / action |
|---|---|---|---|---|
| F01 | Transfer list density | Compact, detailed, adjustable | High-volume operators vs occasional users | Compact already selected; show as reaffirm/reconsider, not blank |
| F02 | Add flow | Immediate, configuration screen, quick-add with expandable options | Frequent adders vs people choosing files/location | Previously chosen quick-add; cite authority |
| F03 | Combined environments | Unified, tabs, switchable | Multi-account operators vs single-server users | Unified already selected; show optional reconsideration only with new evidence |
| F04 | Remove transfer | Separate remove/remove-and-data actions vs option dialog | Bulk operators vs users concerned about unintended deletion | Safety invariants independent of preference |
| F05 | Sorting | Fixed defaults, saved per-view sort, adaptive | Large heterogeneous libraries | Genuinely open |
| F06–F25 | Other researched dimensions | Only include after grounded discovery | Identify specific constituencies and contrary testimony | Varying state, do not invent |

If F01–F03 are already decided and the report adds no material contrary evidence, the survey should **not** re-ask them as if undecided. They can appear in a “Your established directions” section with an optional challenge/reconsider affordance. If the user requests a complete catalogue, include all 25 while labelling state and provenance.

## Example decision card

**F05 · Sorting transfers · Optional / undecided**

“How should sorting behave when switching between environments?”

- **Saved sort per view.** Restores the user's prior arrangement; complexity is remembering several preferences.
- **One global sort.** More predictable across environments; may be wrong for different workflows.
- **Simple default with an advanced preference.** Low friction initially; control discoverability becomes important.
- **Something else / comment / leave unanswered.**

**Agent's provisional suggestion:** saved per-view sort, **if** the app supports multiple views and the preference persists reliably. This is an engineering recommendation, *not a popularity statistic*. Real evidence would cite competitor behavior and user comments separately, and report conflicting views.

**Relationship:** choosing per-view sorting affects persistence, reset controls and merged local/remote transfer views. Those implications are not additional independent questions unless actual product choices remain.

## Example partial submission

The human answers F05 and F04, adds a new idea on F04, leaves the others untouched:

```yaml
interaction: research-survey-2026-11-03
snapshot: "fictional"
answers:
  - id: F05
    selection: "saved per-view sort"
    comment: "Prefer last-used ordering when reopening"
  - id: F04
    selection: "something else"
    comment: "One remove button, explicit data-deletion choice for bulk operations too"
unanswered: "F01–F03, F06–F25"
```

The agent records two **provisional** design preferences. It doesn't interpret the absence of F01–F03 as a reversal of already confirmed requirements. It doesn't implement F04 without checking project authority or bypass data-loss protections. It can offer to investigate alternatives before a binding product decision.

## Negative controls

- Report requested without survey: deliver report; optional offer can be a short concluding sentence, not 25 mandatory inputs.
- One user complaint copied to five forums: one origin, not “five independent reports.”
- Existing accepted direction: preserve it unless new evidence warrants reconsideration or the owner explicitly reopens it.
- Selected option has hidden dependency: show implication, do not silently alter another established requirement.
- A comment rejects the set of options: classify as `new alternative` or `premise challenge` and reconstruct the choice.
- Agent lacks a working interactive UI: provide a numbered Markdown table or compact text-answer template, not a broken form.
