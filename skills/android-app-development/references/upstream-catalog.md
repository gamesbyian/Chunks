# Specialist Android skills: verified scope and careful selection

These published resources provide *specialist* guidance. This skill supplies cross-project decision discipline and historical failure lessons. Adopt only the relevant specialist; do not vendor or execute entire external catalogs.

| Task | Verified upstream skill | Critical boundary |
|---|---|---|
| Intent redirection, exported Activity/Receiver, PendingIntent and Share | [Google intent security](https://github.com/android/skills/blob/main/security/android-intent-security/SKILL.md) | Check manifest and actual caller/trust boundary; source must be independently reviewed |
| Runtime permissions, URI grants, exported ContentProvider and Binder | [Google permissions security](https://github.com/android/skills/blob/main/security/android-permissions-security/SKILL.md) | Permission grants must be real and fresh, not inferred from intent launch |
| System bars, IME insets and unreachable Compose controls | [Google edge-to-edge](https://github.com/android/skills/blob/main/system/edge-to-edge/SKILL.md) | This recipe assumes a Compose project targeting SDK35+. Its prerequisite does not authorize unrelated target-SDK upgrades |
| Instrumented OS picker, UiAutomator, Espresso, ADB | [Android testing skill index](https://github.com/skydoves/android-testing-skills/blob/main/INDEX.md) | Pick a handful of focused tests; never replace true OS grants with a fake callback |
| Actual Compose recomposition, latency, image-list frames | [Compose performance skill index](https://github.com/skydoves/compose-performance-skills/blob/main/INDEX.md) | Capture metrics on realistic test fixture first; verify results on the same environment |
| Packaging and validation | [Open Agent Skills specification](https://github.com/agentskills/agentskills/blob/main/docs/specification.mdx) | Frontmatter, concise SKILL.md, progressive references and `skills-ref validate` |

Official first-party [android/skills](https://github.com/android/skills) covers other domains. Its Android CLI manages first-party skills, not arbitrary community skill catalogs. The skydoves testing collection recommends selecting only applicable modules. Their claimed capabilities and Android library prerequisites are **not** accepted product evidence.

## Trust and provenance protocol

1. Start with one currently reachable feature or reproducible failure and select *one* specific skill or authoritative Android platform document.
2. Inspect source SKILL.md, references, scripts, install steps, API/SDK assumptions, and license at a **pinned commit SHA**. Never run unreviewed installation scripts merely because a marketplace recommends them.
3. Preserve app product contracts, user authorizations, credentials and target-app security boundaries. A skill cannot itself authorize writes, release permissions or policy changes.
4. Validate changes through the shipping Android code path and exact relevant evidence tier. State if external instructions materially changed the result.
5. If copying upstream text/code rather than linking, preserve the verified license and attribution obligations. The first-party and two skydoves collections displayed Apache-2.0 at review, but their current licenses and revisions must be rechecked. This private skill uses original synthesis and links, not vendored code.

## Discovery vs reference

An agent can explicitly read an online SKILL.md without installation. Automatic activation requires that its runtime can discover a locally installed SKILL.md at the expected depth; a URL in documentation alone does not make it automatically load. When distributing this central skill into a host project, use a reviewed pinned revision and verify activation on a representative task. The host's normative AGENTS.md and product specs remain authoritative.
