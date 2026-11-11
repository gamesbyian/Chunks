# Discriminating decisions instead of generating checklists

## A compact decision sketch

Only for a consequential/non-routine choice, answer in this order:

1. **Outcome and current evidence:** what is the exact intended positive user result? What is observed on the current source/build?
2. **Cue and classification:** what hidden problem family is this? Name the decisive cue and one way this situation differs from superficially similar ones.
3. **Authority and scope:** exact principal, file/URI, provider, state generation, version, user consent, and applicable policy.
4. **Default candidate:** the simplest mature mechanism available today, with links to authoritative semantics. Research before inventing a replacement.
5. **Mental simulation:** normal path and most consequential alternate path, including how a false-success claim would arise.
6. **Reversal evidence:** the smallest observation that would change the recommendation. Don't over-investigate incidental unknowns.
7. **Decision:** accepted / provisional / rejected / deferred; reason, tradeoff, actual proof, remaining unverified tier, and explicit trigger for reevaluation.

Record this in the existing issue/PR/decision log. A serious architecture choice can use a short ADR; normal work should be far lighter. Do not spawn duplicate plans.

## Escalation rules

**Choose conventional and move forward** when user intent is clear, the platform primitive directly supports it, effects are reversible and no observed counterexample threatens the positive path.

**Compare real alternatives deliberately** when multiple valid policies apply (Replace vs Keep Both, sync vs snapshot, eager vs deferred import), when source evidence conflicts or the damage of a wrong choice is substantial. Enumerate tradeoffs and ask which observation discriminates between alternatives. Never turn a product choice into an unauthorized technical default.

**Escalate product authority** only when a materially unresolved user intent/principle cannot be learned from project decisions or technical evidence. A user can delegate, defer or qualify an answer; don't treat silence as approval.

**Stop only the unsafe capability** when its specific mutation is unreviewed or demonstrably harmful. Do not block unrelated stable paths with a universal safety gate unless they share the same reachable threat.

## Two counterfactual directions

- **Remove the proposed constraint:** what real harmful behavior becomes possible? If none is demonstrated, test whether the safety gate is invented or overbroad.
- **Remove the proposed mechanism:** does ordinary user intent still succeed through existing APIs/libraries? If so, justify any additional abstraction with measured benefit.

Then **premortem** the selected option: suppose the feature technically passed unit tests but users report failure after restart, network loss or accessibility changes. What missing boundary would explain this? Turn the answer into one affordable production-connected acceptance case.

## Reversibility and proportion

Mistakes with high repair cost (unintended deletions, cross-account writes, lost private data, insecure credential routes) merit heavier evidence and independent review. A low-risk, reversible layout tweak can proceed with representative screenshots and focused tests. This is qualitative prioritization, not a pseudo-scientific risk score.

## Failure handling

After two attempts without stronger positive-path evidence, recheck the initial classification, current production caller, upstream API, unauthorized assumptions and whether the branch's original acceptance is stale. Distinguish test harness vs product bug. Report the exact outcome reached: requested, accepted, OS picker shown, operation attempted, durable journal, hash/byte verified, real device accepted and shipping, each with its own source.
