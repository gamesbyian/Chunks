# Mechanical procedure chains: preserve truth across composed skills

**Status:** original editorial execution-planning method, not a measured improvement or an app-testing tool. The compiler lists obligations. It does not retrieve evidence, execute steps, choose user preferences, verify URLs, or authorize changes.

## Use the smallest sufficient chain

1. **Start from a user-observable outcome.** Read current owner decisions and the actual shipping entrypoint before selecting skills.
2. **Select a procedure using its causal trigger and exclusion.** Match actual state, identity, permission, lifecycle, user-action or evidence boundaries. A task word such as “storage” cannot by itself select a storage procedure. If no trigger survives its exclusion, make the direct routine change and run the focused check.
3. **Record the evidence inventory.** Each source fact has an ID, type, locator, claim and one of four statuses: *observed* (observation recorded, still bounded), *documented* (source assertion), *hypothesized*, or *unknown*. The compiler can check the fields but cannot determine whether the claim is true.
4. **Bind each required input.** Map each declared procedure input either to a source fact ID or to a named output of a **previous, non-skipped** procedure. Never invent an input to quiet the validator. When knowledge is missing, preserve a source claim as *unknown* and specify the research needed.
5. **Check exclusion conditions first.** Record the trigger sources, the fact that the exclusion was checked, and its result. A skipped stage supplies no outputs. An active consequential procedure overrides a self-applied routine label rather than disappearing.
6. **Compile a nonexecuted plan.** Execute the local compiler on the normalized JSON case. Inspect its named steps, input dependencies, unknown evidence, unresolved gates, source pattern IDs and independent oracles.
7. **Acquire evidence and execute separately.** Use authorized tools to inspect exact sources and perform the permitted engineering work. Record each actual output, its time/version/scope, and tests that actually ran. A planned output remains *planned_not_observed* and an oracle remains *not_run* until independent real-world evidence exists. A downstream procedure cannot promote an unexecuted upstream output to a verified fact.
8. **Stop or revise.** Stop research when further plausible answers would change neither implementation nor acceptance. If evidence disproves a trigger, drop the corresponding stage and recompile. Preserve discarded hypotheses and exceptions as conditional lessons.

The compiler produces a **symbolic plan**. All operations are explicitly marked *not_run*. When a source fact is hypothesized or unknown, its dependent stage is marked *needs_source_evidence*. When a stage consumes an earlier proposed output, it is marked *depends_on_unexecuted_stage*. These labels direct work; they do not prove outcomes.

## Examples and commands

A synthetic remote-write case combines **PROC-005** (uncertain remote writes) with **PROC-010** (alternative design challenge). The second consumes two proposed outputs of the first; it must not act on them until they are actually established. The routine typo case intentionally selects no procedure and returns an empty plan.

From the repository root:

    python3 skills/mechanised-expertise/scripts/compile_procedure_chain.py skills/mechanised-expertise/examples/procedure-chain-remote.json
    python3 skills/mechanised-expertise/scripts/compile_procedure_chain.py skills/mechanised-expertise/examples/procedure-chain-typo.json
    python3 -m unittest discover -s skills/mechanised-expertise/tests -p 'test_*.py' -v

## Important limitations

- A syntactically valid locator or declared trigger does not establish real provenance. Verify original documents, current project code and source scope before using conclusions.
- Explicit procedure selection avoids pretending a keyword classifier can reliably infer expert judgment. The earlier cue/question operators generate *candidate* investigations; real source observations decide activation.
- A dependency graph is structurally checked but not semantically proved correct. Review whether an output really answers the receiving procedure's input.
- Both examples are synthetic teaching cases. Neither is evidence about Chunks or any other production app.
- This does not perform software changes, verify real device behavior, claim that a skill improved agents, or authorize operations outside the current user and repository scope.

The general invariant is **separate selection, investigation, execution and observed result**. A well-connected plan is still a plan.
