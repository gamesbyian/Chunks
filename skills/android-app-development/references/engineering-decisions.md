# Engineering decisions: accelerate without fictional shortcuts

## Prioritize the shortest line to an observable user journey

Work from actual production entrypoint to state transition, platform boundary and separately observed postcondition. An isolated helper or research fixture is an intermediate result. The PR must repair an observed harmful failure, ship a bounded normal journey or eliminate a measured blocker on that path. Keep a separate catalog of remaining feature obligations; do not inflate scenario completion with build safeguards.

Put this six-line card in the **existing issue/PR**, not another competing planning document:

1. **Outcome:** The normal action that will become usable or more reliable.
2. **Evidence:** Source SHA, reproducible failure/observation, impact; label inferred or hypothetical claims.
3. **Authority:** User's intent, operation, provider, principal, file/URI and product policy; what is expressly *not* authorized.
4. **Intervention:** Smallest conventional shipping-code change; alternatives rejected and why.
5. **Proof:** Positive connected scenario, one meaningful hostile/interrupt boundary, exact-head checks and distinct physical/provider tier if applicable.
6. **Exit:** Merged/open/reverted/superseded, what remains unverified, and the next runnable action.

## Rationality review for new G0/P0 gates

Before imposing a blanket constraint: (a) name a reachable observed defect or credible high-impact threat, (b) identify whether user consent covers normal behavior, (c) check upstream library/Android contracts and typical comparable apps, (d) isolate the affected operation/provider, (e) propose smallest effective mitigation, (f) test a positive normal user flow and explicit hostile control, (g) record decision and supersession. Positive operation must not remain disabled forever merely because a negative harness looks safer.

Checks that a safety rule is too broad: it requires a custom native writer for standard library behavior; the only reproducer is an authorized replacement; every adapter/provider is blocked by a scenario applicable to one; it cannot state an observable counterexample; or it lacks a production caller.

Two non-progressing revisions should trigger a premise audit. Retain genuine harmful-file/data protections even when an overbroad premise is retired.

## Architecture and ownership

- Map the single authoritative model for destination, selected item, identity, persisted operation and result. Distinguish UI state from irreversible command state. Expand architecture only when two shipped routes demonstrate a real shared invariant.
- Prevent parallel agents from editing the same native service, Room schema, Compose navigation, GitHub workflow or protocol adapter simultaneously. Independent lanes should own different files and acceptance.
- Prefer one clean-main integration PR per behavior. Stacked research branches can be useful but cannot be merged on parent-head evidence: reconcile deltas against the final main and re-run relevant gates.
- Make stale docs subordinate to one current queue and normative contracts. Historical notes remain evidence, never competing instructions. Do not spawn another documentation PR for each test.
- End by recording true user-visible coverage, accepted tests, unverified work and linked evidence. Do not treat commentary, TODOs or an agent's self-report as verification.

## Refuse shortcuts that destroy user outcomes

Do not downgrade HTTPS/SFTP/FTPS, fake an OS picker grant, disable CI assertions to pass, call a synthetic RPC authorization proof, silently unmount/clear app data, weaken accessibility, rename Move to Copy, or auto-retry a possibly applied write. Use a separately restricted/disabled capability with truthful UI if the real adapter is not yet safe.

## Negative control for this skill itself

Given Chunks #568, an agent should recommend user-intent clarification and original libtorrent semantics **before** committing a custom JNI storage architecture; given #809, the same agent should insist on refusal of a genuinely unsafe exporter and a positive provider-specific replacement. Any instruction causing either the wrong universal ban or unchecked write is a regression in this skill.
