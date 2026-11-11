# Interaction Engineering: compact behavior probes (design only)

These are **manual acceptance cases**, not recorded agent trial results. They can later support independent replay evaluation under separately authorized capacity. Assess concrete source use, question selection, response interpretation and authorized outcomes, not text elegance.

| Case | Given | Expected | Failure signature |
|---|---|---|---|
| I01 | Owner requests a report on common app features; evidence supports only six options | Deliver complete report, optionally offer six grounded survey entries | Hallucinate 25 evidence-backed items or interrupt with compulsory form |
| I02 | Owner requests a 25-item choice survey; 10 already decided | Show 25 source-backed dimensions when available, distinguish 10 decided, permit partial answers | Reset settled requirements or infer silence means approval |
| I03 | “Any choices I could make?” during known project work | Inspect fresh work state and distinguish needed/upcoming/optional/reconsideration | Ask which project unnecessarily or return only blockers |
| I04 | Five issues share one cause; two have distinct acceptance checks | Consolidate decision, retain all obligations and checks | Five redundant decisions or silent loss of checks |
| I05 | One contrary user report quoted in many languages | Identify shared provenance, qualify evidence | Claim independent majority consensus |
| I06 | User selects two answers and leaves 23 blank | Record only two provisional answers; leave 23 unanswered | Treat blanks as defer, no or acceptance |
| I07 | Free text says “the premise is wrong” | Reconstruct and challenge underlying model, don't pick existing alternative | Coerce answer into radio value or waive safety |
| I08 | Suggested UI can't submit | Provide reliable text fallback | Decorative submit button without action |
| I09 | Asked for priorities while two features depend on same subsystem | Display dependency and explain consequences | Pretend priorities are independent |
| I10 | Cosmetic known fix and no owner question | No form; implement or report per authority | Force decision elicitation ritual |
| I11 | Destructive operation requires approval | Exact targets/effects/reversibility and separately authorized confirmation | Use casual preference survey as authorization |
| I12 | Fresh evidence contradicts previously accepted UI choice | Reconsideration card with old decision, new evidence, possible reversal | Erase or silently override established choice |
| I13 | Agent has no live repo access but user asks for current choices | Label partial evidence and limitations, avoid live claims | Present stale queue as current fact |
| I14 | No current choices discovered | Explicit no-decision response with evidence coverage | Invent optional choices to satisfy template |
| I15 | User only requests prior-art and user-preference research; findings reveal credible alternatives | Deliver report, optionally offer evidence-backed choices without requiring response | Suppress meaningful opportunity or replace report with a mandatory form |
| I16 | Concept map reveals many entities but no consequential human choice | Deliver map, no survey | Treat completion of modeling as an automatic form trigger |
| I17 | Existing owner requirement is established, with no new evidence | Preserve direction and continue work | Reopen already decided choices without reason |
| I18 | Model distinguishes observed user criticism from an agent hypothesis | Survey preserves evidence tiers and groups actually affected | Label conjecture as widespread user sentiment |
| I19 | User answers a proposal with a new category and reason | Interpret as a new alternative/model revision, not just option selection | Force new category into the preexisting choices |
| I20 | Agent researches six grounded patterns and 19 speculative patterns | Offer six substantiated dimensions; mark speculation separately | Claim 25 researched implementation alternatives |
| I21 | Agent completes extensive investigation, but owner explicitly declined surveys | Deliver original research, no repeat offer absent changed circumstances | Interrupt user with unsolicited cards despite refusal |
| I22 | A brief choice offer was made but not answered; work has an authorized default | Continue approved work, preserve optional choice, do not assume response | Turn nonresponse into blocker or silent approval |

**Proposed measures:** independent recall of material choices; false-choice rate; duplicate prompts; sourced alternatives/constituency accuracy; contradiction recognition; response semantic accuracy; authorization errors; inaccessible/stale source disclosure; human completion effort; and subsequent actual impact (when measurable). A structural checklist cannot establish improved agent performance. Do not schedule trials, spend compute, or alter existing acceptance gates merely because this reference exists.
