# Converting source advice into executable agent skill procedures

**Status:** original editorial transformation. The source corpus records what publishers advise; this method converts selected advice into explicit operations. Neither source popularity nor a successfully compiled procedure proves effectiveness. Existing user decisions, live code and verified product behavior take precedence.

## Source-to-mechanism compilation

For each individual source recommendation:

1. **Resolve identity:** record the original rule ID, pinned source location, relevant framework/version, publisher lineage and whether the text is original, derived or paraphrased. Never create a new source count for our analysis.
2. **Find the actual task trigger:** identify an observable condition in a feature, code path, user journey or test. A keyword such as “file,” “security” or “UI” by itself cannot activate a procedure.
3. **Record the exclusion:** state the nearest case where the advice would waste work or cause harm. If no useful exclusion can be identified, mark the transformation incomplete and investigate.
4. **Declare input facts:** list the evidence items an agent must actually have before taking action. Cite the project contract, source path, original user account, platform document, or measurement that can supply them. Unknown inputs stay unknown.
5. **Compile transformations:** write a short ordered sequence of operations using inspect, enumerate, trace, compare, reproduce, measure, route, record or verify. Each operation must name the data it consumes, the specific action it performs, and the new artifact it produces. An operation may only consume inputs or outputs of previous steps. No undeclared leaps.
6. **Add a decision gate:** formulate one falsifiable choice-changing question with two plausible and materially different actions, plus the cheapest real observation that distinguishes them. Where no material alternative exists, select the direct local repair.
7. **Select the mapping gate:** do not build a concept map unless a missing directed relationship could change the decision. If a map is needed, audit retrieval gaps, aliases, absent relation types and known unlinked edges before calling anything novel.
8. **Name the independent oracle and stop rule:** distinguish compilation, simulation, emulator, installed device, real provider and observed user experience. Stop investigating once remaining answers cannot change a user outcome or acceptance test.
9. **Review source fidelity:** have a reviewer compare the proposed procedure to the actual pinned source and label which parts were supplied by that source versus added by the editorial transformation. Preserve disagreement and unresolved assumptions.
10. **Retain results only after use:** record whether the trigger was useful, what was answered, whether the decision changed, what evidence actually ran, and which steps were unnecessary. Do not promote an untested editorial method into authoritative advice.

## Machine-readable examples

The hand-curated [30 source-linked procedures](../assets/source-mechanisms-v1.json) cover the entire pinned sample and are a first implementation across requirements, existing architecture, async UI state, causal debugging, uncertain remote writes, accessibility, profiling, conceptual gaps, end-to-end acceptance and design alternatives.

Each record has: original source ID, activation, rejection, named input facts, ordered typed operations, output dependencies, two-sided decision gate, discriminator, independent oracle and stopping rule. The [source-pinned table](../assets/source-to-expertise-v09.csv) and the [eight-source addendum](../assets/source-to-expertise-v11-additions.csv) retain original publication provenance without rewriting original records.

Run:

    python3 skills/mechanised-expertise/scripts/audit_source_mechanisms.py

The validator rejects unknown source IDs, forward references, missing inputs, duplicate outputs, unknown operations, generic intuitive commands and missing alternative decisions. It does **not** establish source accuracy, equivalence to experienced practitioners or empirically better outcomes.

**Coverage is explicit:** these 30 curated procedure examples cover the original 22 pinned practice records plus eight separately pinned Android specialist additions, all of which remain a small subset of the 1,073 recommendation records in the research corpus. Conversion is a separate workstream; do not automatically mark all source records as reviewed or mechanistically complete.

## When to stop compiling

If a source sentence is merely a preference with no identifiable trigger, independent oracle, or scope, preserve it as advice rather than fabricating a procedural rule. If the source merely describes a library API, classify it as a reference to consult, not a command to install it. If the project already satisfies the recommendation and the remaining decision is routine, record non-applicability and proceed without additional research.

## Specialist source extension

The [additional eight source cases](source-synthesis-extension.md) cover installed Room migration, scheduling choice, worker progress observation, Compose test seams, reusable-state ownership, error observability, runtime grant revocation and untrusted intents. Their source lines, author prescriptions, platform scope and **editorial** proposed operations remain separate. The historical v0.9 source table is unchanged. CI validates both tables and exactly 30 corresponding procedural recipes.
