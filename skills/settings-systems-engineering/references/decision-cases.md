# Decision cases and counterfactual evaluations

These are *test prompts for agents*, not proof that this Skill improved behavior. Evaluate blind: present the problem without the expected answer, then score decision, implementation boundary and meaningful evidence.

**Case A: Simple app / should not build a registry.** A one-screen app has one theme switch already backed by a stable repository. Expected: extend existing pattern; no schema renderer, no new store, basic persistence/visual check.

**Case B: Move global preference.** App currently exposes Keep/Archive/Delete original source in each Add flow; owner decides this is a global default under Options. Expected: keep one storage owner and existing lifecycle safety; move UI only, eliminate Add per-transfer copy, preserve exact archive-after-ingestion semantics, migrate only if necessary, reconcile changed tests before costly run.

**Case C: Same control, different clients.** Three remote profiles each have a download directory picker. Expected: same definition/component, separate instances keyed by exact immutable profile ID, one-off Add folder must not change saved client default; reset client override cannot touch another profile.

**Case D: Explicit OFF after default change.** Existing user disabled network metadata lookup; a fresh-install default becomes ON. Expected: preserve explicit OFF after upgrade, do not derive consent from a new default, test actual prior storage fixture and privacy effect.

**Case E: Complex remote editor and uncertain save.** Editing server hostname, credentials and selected instance requires one coherent Connect command; timeout may mean it applied. Expected: validated draft, explicit commit where needed, frozen target, truthful uncertain state, read-only reconciliation, no blind retry; not per-character immediate RPC.

**Case F: Stale screenshots after menu redesign.** Tests assert old category labels/locations while all underlying behaviors remain required. Expected: trace authoritative contract, rewrite navigation selectors, retain behavior/a11y/migration oracles, batch relevant integration run; neither blindly fix UI to old tests nor delete protective tests.

**Case G: Policy setting locked by system capability.** Android notification channel is disabled by OS and app has a preference for an event type. Expected: distinguish OS permission/channel status from in-app preference; report disabled reason and link to supported OS settings; no false "enabled" outcome or hidden control still in focus tree.

**Case H: JSON Schema temptation.** A Kotlin Compose app with twelve static preferences is asked to be modular. Expected: use typed Kotlin descriptions or AndroidX framework pattern; JSON Schema/remote configurable form engine only if true interchange/dynamic requirements justify it. Compare type safety, renderer exceptions and maintenance.

**Case I: Owner wants new settings for everything.** Frequent sort and an immediate Delete Files command appear on a Transfers page. Expected: keep contextual sort as transient or explicitly remembered view state according to owner intent, keep Delete as authorized operation; do not put them both into global Options registry.

**Evaluation receipt:** case ID | predicted decision | agent decision | source/owner authority respected | correct scope/effect | chosen evidence tier | avoidable extra machinery/tests | remaining unknown. Include a no-change case and a case where deeper testing is justified.
