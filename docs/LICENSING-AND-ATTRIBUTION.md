# Chunks distribution, public-domain intent and third-party attribution

**Status:** owner direction recorded 2026-11-05; project-wide binding intent, implementation/license release audit pending. **Decision:** [D-FREE-DISTRIBUTION-AND-PROVENANCE-20261105](DECISIONS.md). **Scope:** source repository, Android APK, included libraries, downloaded or bundled art/fonts, test fixtures and any future platforms.

## Owner's intent

Chunks is to be shared **freely**, without sale, royalties, proprietary control, an asserted exclusive economic interest or an attribution/ownership claim by the owner over work that can legally be relinquished. The project will accurately preserve credits for the people and projects that actually authored upstream material, and comply with their applicable license terms. The owner does not require personal credit or credit as the sole author. Do not add copyright, branding or commercial exclusivity assertions in the owner's name just because a packaging template expects one.

**Practical legal boundary:** copyright and authorship can arise automatically by operation of law; an owner cannot waive rights belonging to other contributors, upstream projects or artists. A free download and a gracious attribution are not substitutes for fulfilling a copyright license. Source and artifacts cannot accurately be described as *entirely public domain*, *all Unlicense* or *all owned by Chunks* until the repository and all included material have been audited and their permissions established.

## License posture and options

- **First choice for independently owned original Chunks material, once ownership/contributor consent is verified:** public-domain dedication with the [Unlicense](https://opensource.org/license/unlicense) (SPDX Unlicense), which includes a public-domain dedication and a permissive fallback. [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) is another possible dedication to evaluate. Preserve notices on any files for which only specific licenses were granted.
- **For any imported GPL-3.0 code from Flood, Transdroid or Electorrent:** investigate exact files, copyright holders, modifications and combined-work compatibility; distributing a combined covered work can entail GPL licensing terms, complete corresponding source and required notices. Merely naming an upstream project in credits does not suffice. A GPL-compatible distribution is consistent with free and nonproprietary intentions, but may constrain what downstream redistributors must do. Do not import GPL code before reviewing the actual distribution impact.
- **For already-included and other third-party libraries/assets:** comply per dependency/file and preserve SPDX metadata, LICENSE/COPYING, NOTICE, modification notes and any legally required source offer; some dependencies may impose incompatible requirements. Fonts, icons, posters, metadata provider images and user-supplied torrents have separate provenance and use rules. No blanket project-wide license erases those distinctions.
- **Original vs combined result:** a public-domain dedication of eligible new original components can coexist with third-party portions under their own licenses. The exact licensing status of a binary/combined derivative may differ from that of its standalone original parts. Choose the release packaging language only after dependency audit, not by treating all of the repository as owner-authored.

**No definitive root LICENSE has been added by this documentation change.** At the time of this review the repository lacked a root LICENSE, UNLICENSE and COPYING file. This is an outstanding distribution governance task, not evidence that existing code is public domain or that copying is unrestricted. After auditing ownership and dependencies, decide whether to add a root Unlicense plus exceptions/third-party notices, use a broader GPL-compatible project license for combined imported code, or retain finer-grained per-component licensing. The goal is the least restrictive **lawful** path consistent with the owner's intent.

## Fast reuse without avoidable licensing churn

For a new BitTorrent remote adapter, first consult vendor RPC specifications and study Flood/Transdroid/Electorrent architecture, error semantics and independently reproducible tests; write compact Kotlin code using normal Android dependencies. This often avoids a wholesale port of Node or older Android implementations and keeps GPL derivative/licensing questions narrow.

Directly copy a source file or library only after recording a concrete engineering benefit over an independent implementation; source URL and exact commit; original copyright and license; compatible build/distribution plan; changes; notices; test plan. If a GPL import is the fastest safe engineering route and distribution obligations can be met, the owner has authorized evaluating that route. Do not reject a useful technique merely because its author deserves credit, and do not treat willingness to give credit as unlimited permission.

## Attribution and provenance inventory (required before release)

Maintain one machine-readable manifest plus a human-readable Credits / Third-party licenses screen or packaged document. Record:

| Field | Required information |
| --- | --- |
| Component | project, dependency, file(s), version or source commit, repository URL |
| Origin | actual author/copyright holder where known; original license/SPDX and license file |
| Inclusion | direct copy, modified copy, runtime dependency, linked library, generated/bundled asset, reference-only research |
| Obligations | attribution, keep notice, modification marking, corresponding source, redistribution/license compatibility, patent/trademark boundaries |
| Delivery | where the license/NOTICE/credit is available in repository and distributed APK; source-link or archive when required |
| Verification | audit date, responsible reviewer, unresolved exceptions, automated license/SBOM result |

Reference-only inspiration and official protocol specifications should be credited in technical docs without incorrectly labeling them included source-code dependencies. Chunks-original elements can be released without demanding personal attribution, but preserve real third-party notices.

## Contributor and release policy

1. Contributors must have the right to submit their changes and knowingly agree to the applicable permissive/public-domain or project component license. Do not assume contributors waive their own rights merely because the owner wishes to. Keep accurate Git history and any legally mandated notices. No compulsory copyright transfer to the owner.
2. On each new direct dependency or copied asset, record the exact license and source and review compatibility with the intended release. Fail the release audit when a material inclusion has unknown legal provenance or irreconcilable terms; do not mark harmless architectural references as copied code.
3. Before shipping an APK or source release, inventory Gradle/Maven, native libraries, assets, source inclusions and generated distributions. Preserve installable license/notice access, exact source references and GPL corresponding-source duties where applicable. Separate mandatory notices from optional acknowledgements.
4. No ads, donation demand, paid tiers, authorship gate or restrictive commercial-rights claim is implied by this policy. Other authors retain whatever rights their license grants; users' freedom to copy/reuse follows the actual licenses, not a contradictory marketing slogan.
5. If legal provenance is ambiguous, annotate the precise blocker and continue with independent implementation. Do not delay rTorrent reliability, universal UX or normal non-distribution CI on speculative global license rewrites.

## Status and follow-through

**Approved intent:** unrestricted *eligible original work*, gratis distribution and accurate upstream attribution. **Not yet completed:** comprehensive provenance audit, explicit root and per-component licensing, credits/NOTICE in binaries, optional GPL direct-code decision and final release compliance. These are governance/packaging tasks to be coordinated with existing [#10](https://github.com/gamesbyian/Chunks/issues/10) release work, not a new P0 app-development campaign.

Related: [remote adapter contract](REMOTE-BACKEND-ADAPTERS.md), [Android TV/backend roadmap](ANDROID-TV-AND-REMOTE-BACKENDS.md), [release build](BUILD.md), [documentation standard](DOCUMENTATION-STANDARD.md), [reuse audit](research/OPEN-SOURCE-REUSE-2026-10-29.md), [decision ledger](DECISIONS.md).
