# Settings Systems Engineering

A portable, progressively disclosed decision and implementation Skill for application preferences and Options menus. Use it when several persistent controls, scopes or screens need coordinated change. It is deliberately *not* a general requirement to generate schemas for every app.

Start at [SKILL.md](SKILL.md), which defines activation, rejection conditions and the S0–S8 procedure. Open one targeted reference only when the problem requires it:

- [Registry and effective scope](references/registry-and-scopes.md): definition versus scoped instance, resolution, reset and ownership.
- [UI and interaction](references/ui-and-interaction.md): page composition, contextual placement, visibility, search, accessibility and commit semantics.
- [Migration and proof](references/migration-and-proof.md): old values, explicit OFF, tests, production outcomes and economical validation.
- [Decision cases](references/decision-cases.md): nine blind evaluation scenarios, including cases where avoiding a generic registry is correct.
- [Typed Kotlin/Compose example](examples/kotlin-compose-projection.md): conceptual illustration, not a drop-in framework.
- [2026 source audit](research/prior-art-audit-2026-11-15.md): official platform guidance, reference products, public Agent Skills and other agent instruction mechanisms, with evidence limitations.

The Skill is independent of Chunks, Retrofit and any private project. The host must supply its actual owners, value scopes, UI and user authority. [Skills portability](../PORTABILITY.md) governs cross-project deployment and local relative references. Changes to the Skill are documentation/procedure changes until an agent's behavior is independently evaluated; app functionality remains untested by this package.
