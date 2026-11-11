# Find one procedure without loading the full catalog

**Use after inspecting current facts**, not as a substitute for them. Select by a demonstrated causal trigger, check the explicit `exclude` condition, and inspect a full procedure only if different possible answers could alter implementation, UX, permission, or the acceptance test.

The complete 30-procedure JSON is ~79 KB. Do not preload it. From this skill directory run:

```bash
python3 scripts/procedure_lookup.py                  # Compact IDs, triggers and exclusions
python3 scripts/procedure_lookup.py --id PROC-005    # One complete bounded procedure
python3 scripts/procedure_lookup.py --json           # Compact index for a tool consumer
```

An agent without shell access can locate the matching `PROC-###` identifier from the table, then open or search `assets/source-mechanisms-v1.json` for that exact entry. Never treat a title match as an activated mechanism.

| Domain | Procedure IDs and purposes |
|---|---|
| User needs and UI states | `PROC-001` missing feature requirements; `PROC-003` loading/empty/stale/failure; `PROC-006` accessible task equivalence; `PROC-011` adaptive layouts; `PROC-015` RTL meaning; `PROC-027` reusable control state |
| Architecture and decisions | `PROC-002` current architecture; `PROC-010` strongest alternative; `PROC-014` coroutine ownership; `PROC-024` background scheduling; `PROC-025` worker event migration |
| Network, identity and trust | `PROC-005` uncertain remote writes; `PROC-020` device transport capability; `PROC-022` exported app-agent API; `PROC-029` URI/permission grants; `PROC-030` intent redirection |
| Testing and quality | `PROC-009` user-journey acceptance; `PROC-012` test tier; `PROC-013` app reset vs process stop; `PROC-017` installed QA; `PROC-019` reviewer claims; `PROC-021` media/DRM; `PROC-026` Compose UI tests |
| Debugging, performance and error truth | `PROC-004` causal debugging; `PROC-007` measured performance; `PROC-016` release profiling conditions; `PROC-028` observable errors |
| Research and collaboration | `PROC-008` conceptual blank-space audit; `PROC-018` delegated-work boundaries |
| Persistence and migration | `PROC-023` installed Room schema upgrade |

**Selection algorithm:** classify the actual observed task boundary; use the table to identify candidate IDs; read triggers *and exclusions* via `--list`; reject false matches; open only the qualified procedure with `--id`; bind inputs to inspected sources; follow its ordered operation dependencies; stop when no choice-changing unknown remains. An `unknown` input remains unresolved, not implicitly false or observed.

The table is an index, not an exhaustive rule list or proof that a user needs a feature. The [source-to-procedure provenance guide](corpus-to-procedure.md) and [original pinned evidence](../assets/source-to-expertise-v09.csv) explain which prescriptions were published and which operations are original editorial transformations.