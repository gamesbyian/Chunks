# Concept Model Engineering agent skill

A **standalone capability** in the [skills architecture](../CAPABILITY-ARCHITECTURE.md). It can construct and deliver models directly; mechanised inquiry procedures may help with individual steps, but are never a prerequisite.

A portable companion to [Android App Development](../android-app-development/SKILL.md), designed to serve *any* research, design, engineering or knowledge-synthesis workflow. It formalizes an existing working method represented by [Chunks human concept graph](https://github.com/gamesbyian/Chunks/blob/main/docs/research/BITTORRENT-CONCEPT-GRAPH-2026-10-29.json) and [AYA's Application Understanding Framework](https://github.com/gamesbyian/AYA/blob/main/docs/understanding/README.md).

Central principle: **qualify map completeness before calling empty regions genuinely unexplored**. The method protects the creative frontier by searching for unrepresented existing evidence, misleading labels, false conceptual mergers, missing relation types and already-shipped counterparts first.

Contents: [SKILL.md](SKILL.md), [frontier qualification](references/frontier-qualification.md), [model and evidence workflow](references/model-and-evidence.md), [academic and external skills](references/research-and-skills.md), [project precedents](references/project-casebook.md), a [JSON Schema](schema/concept-model-v1.schema.json), and a [fictional example](examples/cross-system-journey.json).

This is a **research/procedure skill**, not a new feature request in Chunks or any other app. No external libraries or network connectors are required. Its offline checker validates referential integrity and status consistency, not conceptual truth or actual novelty. It is not installed automatically into the other repositories. Do not copy third-party skill bodies or scripts without review/licensing.

Run `python3 scripts/check_model.py examples/cross-system-journey.json` and `python3 -m unittest discover -s tests -v` from the skill directory.


Mechanical construction and audit: [M1–M7 operations](references/mechanical-model-operations.md). For M2–M3 across multilingual or repeated sources, use the [source ingestion and identity protocol](references/source-ingestion-reconciliation.md). From repository root use `python3 skills/concept-model-engineering/scripts/audit_coverage.py skills/concept-model-engineering/examples/cross-system-journey.json`; this emits structural review candidates, not claims of novelty or missing features.

Optional reviewed graph repair: [reviewed model deltas](references/reviewed-model-deltas.md) takes an existing model, staged source ledger, and explicit review decisions. The standard-library proposal tool emits additive source/concept/claim changes and a separately written preview, never silently changes the original or turns a synthetic source citation into verified truth. The [teaching review decisions](examples/reviewed-delta-decisions.json) deliberately defer a cross-source relation.

Optional source staging review (also from repository root): `python3 skills/concept-model-engineering/scripts/audit_ingestion.py skills/concept-model-engineering/examples/multilingual-ingestion.json`. It flags declared lineage duplication, potentially identical labels, homonyms, contradictory statements and missing source classes, without merging identities or proving source accuracy.
