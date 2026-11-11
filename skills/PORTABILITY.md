# Portable Skills library

This directory is a **self-contained, project-migratable skill package**. The canonical source lives in GBIDEVELOPMENT/general-purpose, but no agent navigating these installed skills should need access to that repository. Public research citations, published Android documentation and external upstream projects are legitimate external *evidence*, not required local skill files.

## Install in another project

Copy the **entire skills directory**, preserving relative file paths. Copying only SKILL.md files discards references, executable helpers, schemas, examples and integrity checks. Do not copy the canonical repository's AGENTS.md, which contains unrelated project-specific authority. Instead, add the relevant routing paragraph to the destination's own AGENTS.md.

From a local checkout containing the source and destination, use the offline helper (dry-run by default):

    python3 skills/scripts/install_into_project.py --source-root SOURCE_REPO --project-root TARGET_REPO --dry-run
    python3 skills/scripts/install_into_project.py --source-root SOURCE_REPO --project-root TARGET_REPO --source-revision VERIFIED_SOURCE_SHA

The helper copies files, records an explicitly supplied source revision and validates links. Existing mismatched files are never overwritten without --replace. Replacement is file-scoped and does not erase unrelated destination files. After the copy, review local adaptations before using --replace; neither an update nor a copy grants agents permission to implement product features.

Validate either tree locally without network access:

    python3 skills/scripts/check_portability.py --skills-dir skills
    python3 -m unittest discover -s skills/tests
    python3 skills/scripts/audit_instruction_completeness.py --skills-dir skills --check-contracts

**Host routing snippet**, to adapt into the host's own AGENTS.md:

> For substantial new projects, first consult skills/project-inception/SKILL.md. For already specified Android work, use skills/android-app-development/SKILL.md. For standalone source and ecosystem investigations use skills/evidence-acquisition/SKILL.md; for library/API/code reuse use skills/engineering-resource-discovery/SKILL.md; for multi-feature product contracts use skills/product-specification-engineering/SKILL.md. For other tasks choose one primary skill using skills/CAPABILITY-ARCHITECTURE.md, and load supporting references only for consequential dependencies. Local project instructions, owner decisions and security boundaries remain authoritative.

For a migrating project's **documentation maintenance**, map the destination's existing AGENTS/onboarding, specs, plans, live issues/queue, handoffs and evidence records by role using [Operational Documentation Integrity](references/operational-documentation-integrity.md). Do not prescribe source-project filenames, directories or private history; preserve host authority and avoid adding duplicate records just to match a template. The copy procedure does not synchronize any project documents automatically.

Do not use absolute GitHub URLs as internal navigation. Link among skills using local relative paths. External references should be clearly identified as sources to consult, not runtime requirements. Shared examples must label project-specific facts as examples, never universal constraints.

## Portability contract

- A SKILL.md has recognizable frontmatter with a name matching its folder.
- Every local Markdown link resolves *inside* the skills package, with case-sensitive path checks where available. Cross-skill links are relative.
- Internal links must not point at the source repository's main branch or a stale commit. Keep source provenance in VENDOR-SOURCE.md, not as hidden navigation dependencies.
- Scripts use paths relative to their own files or an explicit --skills-dir/--source-root, not assumed checkout names or directory layout outside skills/.
- A portable skill must not require source-project secrets, CI, mounted files, private issues or app-specific fixtures for its core instruction procedure. Historical case links may be cited as optional context and labeled accordingly.
- Copy all required schemas, references, examples, scripts and tests. If a referenced dependency cannot be included, change the procedure to explain the limitation instead of leaving a broken path.
- Test the copy from a **different parent directory and repository name**, and verify activation from the destination's AGENTS.md. The skills cannot themselves force an agent runtime to discover them automatically.

## Maintenance

Update canonical first, run the portability checker, then migrate from a verified source SHA. Any project-local edits should be reviewed rather than blindly overwritten. Verify the destination copy and its AGENTS.md after migration. The source's version stamp and the destination's last synced revision have different meanings: a source update never silently updates the destination.
