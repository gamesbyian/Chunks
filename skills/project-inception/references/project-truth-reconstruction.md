# Recover project truth from conflicting code, plans and decisions

**Trigger:** new project inception, long-running project handoff, conflicting plans or a substantial change with stale summaries. **Exclude:** a narrow, already specified fix with current code and acceptance. **Inputs:** user request, reachable repository, authoritative owner instructions, accessible historic sources. **Output:** versioned project baseline and conflict decisions. No broad audit grants permission to access secrets or other projects.

## P1. Fix the investigation boundary

Record repository, canonical main revision and time, requested outcome, affected features, tools actually available, and whether prior conversations/connected records are accessible. Mark NO ACCESS rather than inventing prior owner decisions. A historical branch can be researched without being promoted into current source authority.

## P2. Build a targeted evidence inventory

Read in this order when available, recording exact locator/revision: (a) current user task and owner decisions; (b) AGENTS.md and normative product requirements; (c) actual main code/call path, tracked artifact IDs and generated build; (d) active PR/issue and project work queues, linked acceptance evidence; (e) recent CI/jobs and test execution counts; (f) historical threads/branches if they could overturn the apparent status; (g) external integration contract and its independent version/PR. Scope each retrieval by the decisions it could change, not total repository size.

For each source mark INSPECTED, NOT INSPECTED, INACCESSIBLE, SUPERSEDED, CONTRADICTORY or BACKGROUND. An unresolved project note is not an owner mandate; a merged implementation does not prove an installed feature works.

## P3. Extract atomic claims with authority

Record stable ID, source locator and date, actor and verb, target entity/version, claim, original status (instruction, proposal, implementation, test observation, acceptance, hypothesis), affected user journey, and supersession evidence. Do not choose which claim wins from document filename alone. Separate **normative** (what is required/forbidden) from **descriptive** (what code currently does) and **evidential** (what executed tests observed).

## P4. Reconstruct work and concurrency state

Map actual open PR heads, target branches, overlapping touched files, owned integrations, required checks, merged/superseded work and known issues. Compare PR head and CI SHA; passing checks on another revision cannot qualify the current head. An active PR may contain a proposed design that current main does not implement. Never assume live agents can receive messages unless an explicit shared handoff exists.

## P5. Identify the decision conflicts

For each pair of materially incompatible claims, ask: same actor and exact environment? Same product/version and time? Was an explicit later owner decision made? Is one only code reality and the other intended requirement? If both still apply, record a conflict with consequences and cheapest resolving source; seek human authority only when no inspectable fact resolves it. Do not overwrite owner intentions with what the latest agent happened to implement.

## P6. Reverse trace the product premise

Start from each required user job, trace entrypoint → actual code/state owner → external dependency → observable test → user acceptance. Reverse trace unexplained implementation modules to an authorized requirement. Label REQUIRED/PROPOSED/SHIPPED/VERIFIED separately. Any mismatch becomes a research, spec or qualification task, not an invented gap.

## P7. Produce the baseline and stop

Return: exact source revision; owner confirmed/prohibited; current implementation and tested tier; live PR/CI coordination; known integration contracts; unresolved contradictions; inaccessible sources; suggested read-only next investigation; ready independent slices. Stop once each material next decision has source/status, or missing evidence is explicitly bounded. Avoid reconstructing every archived issue if no plausible decision changes.

**Oracle:** a second reviewer can reproduce two high-impact claims from their original sources, and a previously uninspected PR or executable path does not overturn the selected baseline. **Negative controls:** PR marked draft cannot be called shipped; green unit tests are not installed acceptance; inaccessible owner messages cannot be reconstructed from guesswork; a routine label fix needs no full reconstruction.

## Applying a reconstructed truth to operational documents

This protocol establishes source scope and claim authority; it is not a historical transcript to paste into active plans. When the reconstruction resolves a change that affects what another agent should do, apply the [Operational Documentation Integrity procedure](../../references/operational-documentation-integrity.md) to update only the affected authoritative plan/spec/onboarding/queue/handoff, preserving consequential original evidence in its existing record. If the task is investigation-only or no current instruction changes, report the findings without inventing documentation work.
