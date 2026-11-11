# Android App Development agent skill

Portable, progressively disclosed Agent Skill built from the **actual** engineering histories of [Chunks](https://github.com/gamesbyian/Chunks), [Glimpse](https://github.com/gamesbyian/Glimpse) and [Expository Transfer](https://github.com/gamesbyian/Expository/tree/main/android-transfer). It complements, rather than replaces, the smaller android-evidence-first skill already installed in those repositories.

**Location:** this folder is portable and may be used as a project-local copy. See [portability and installation](../PORTABILITY.md).

## How to use it

- A coding agent with access to this private repository can be instructed to read this directory's SKILL.md and only the relevant references before native Android work.
- For automatic agent activation, install a *reviewed, version-pinned copy* at a path supported by the specific runtime, and add a short AGENTS.md discovery rule in the Android project. Common paths differ by runtime: .agents/skills/android-app-development, .github/skills/android-app-development, or .agent/skills/android-app-development. Confirm with that runtime's current documentation rather than assuming online publication means automatic discovery.
- Preserve local product/architecture/CI rules above this portable advisory skill. Never make installation imply permission for network writes, credential access, releases or merges.
- Run: python3 scripts/check_skill.py from this directory. It checks frontmatter, local references and case-link consistency. Use skills-ref validate on the same directory when installed. No network, Android SDK, dependencies or secrets are needed for the included check.

## Purpose and evidence discipline

This skill is optimized for choices that generative coding agents repeatedly get wrong: speculative universal requirements, incomplete app-to-provider integration, unsafe mutations, confused evidence tiers, excessive architecture, fragmented state ownership, noisy CI and stale PR/issue tracking.

Its history contains a **metadata-level** review of 1,400 PR records (881 Chunks, 156 Glimpse, 363 Expository) observed in a 2026-10-31/11-01 snapshot, plus issue inventory. It does **not** claim all 1,400 diffs/reviews/CI logs have been independently examined. The casebook identifies individually inspected, linked evidence and places limitations beside each claim. Further analysis and a counterfactual evaluation plan live in references/audit-coverage.md.

No actions on the three Android repositories are implied by creation of this central skill. A later rollout should use independent review PRs, version pinning and actual task-based activation checks.

## Reproducible read-only historical inventory

Run from this directory: `python3 scripts/snapshot_history.py --output /path/to/new-local-snapshot.json`. Existing output is deliberately refused unless `--overwrite` is explicitly supplied. Optional GITHUB_TOKEN or GH_TOKEN increases GitHub's read-only API rate limit; no token appears in the output. The script enumerates paginated PRs and separate issues for all three repos, emits titles/metadata but no bodies, review text or CI logs, and detects pagination duplicates and a moving main head. Its output is local only; do not commit it automatically. Test offline: python3 -m unittest discover -s tests -v. A full review of diffs, discussions and Actions still requires separate investigation.

## Version 0.3 refinements

The operating skill now separates quick activation from detailed [Android platform playbooks](references/platform-playbooks.md) and [independent PR review](references/adversarial-review.md). Historical sources are expanded beyond the initial 17 to 33 case studies; the comparison between genuine destructive writes and misguided universal safety constraints is deliberate. These are curated linked issue/PR narratives, not a claim of inspecting all 1,400 diffs or all workflows.

The [upstream skill catalog](references/upstream-catalog.md) remains a pointer for focused official/community specialist material. No upstream scripts or license terms have been copied into the skill. All specialist instructions remain subordinate to project-specific user permission and current source facts.
