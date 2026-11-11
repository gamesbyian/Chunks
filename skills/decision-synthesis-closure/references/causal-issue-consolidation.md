# Causal issue consolidation before human decision synthesis

**Trigger:** several apparent blockers, repeated failures or overlapping requests might share a root cause. **Exclude:** one simple isolated issue, or a request only to summarize source text. **Inputs:** actual affected journeys, source SHA, reproducible symptoms, first failed assertions, logs and claimed requirements. **Output:** cause graph and independent acceptance obligations. Do not infer one root cause from similar issue titles.

## C1. Normalize the observations

For every issue record ID, expected postcondition, actual observed outcome, first divergence/last reached test assertion, conditions, environment, source revision and evidence tier. Split multiple defects hidden in one issue. Mark nonreproduced reports as hypotheses, never observed incidents.

## C2. Propose at least two explanations for consequential failures

Trace shipping entrypoint → state owner → platform/provider boundary → side effect → feedback. Name a common mechanism that could cause several symptoms and a plausible independent mechanism for each. Check whether the cases share a failing precondition, code path, resource, account identity, fixture or runtime state. A shared UI label is not a causal bridge.

## C3. Find the cheapest separating observation

Choose a check that would respond differently under competing explanations: inspect common caller, compare differing fixtures, instrument exact state IDs before and after suspend, run a focused negative control, or review a known-good revision. If the shared-cause hypothesis cannot be falsified, retain UNKNOWN and avoid collapsing obligations into a single resolved blocker.

## C4. Group carefully

Join issue IDs under a cause only when an inspected mechanism explains their actual divergence and fixing it would plausibly alter all relevant outcomes. Keep multiple distinct acceptance assertions under the group. The same change can address several symptoms without implying all are verified. Preserve standalone security, privacy, data-loss and owner requirements even if a root cause is fixed.

## C5. Ask only decisions that change actions

A technical diagnosis that code or test evidence can resolve is agent-owned. A proposed change to feature policy, irreversible cost/risk or owner preference may need approval. Build one decision card per genuine choice, linked to every affected issue, with source-backed alternatives, affected user jobs and independent constraints.

## C6. Reconcile and retest

After one authorized correction, run the focused reproducer and every distinct acceptance assertion that the cause group claims to cover. If one remains red, split/reopen the diagnosis. Update issue statuses by verified observation and source SHA, never by a summary saying "fixed root cause."

**Stop:** all material symptoms have either supported cause groups or explicit unresolved competing explanations; unverified obligations remain traceable. **Oracle:** reverse trace a randomly chosen issue to its observed failure and its still-independent acceptance. **Negative controls:** five identical issue titles with different accounts/causes must remain separate; five manifestations of a single verified rejected permission can share a diagnosis but not erase five user outcomes.
