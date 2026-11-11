# Complexity signatures: recognize the hidden problem, not just the visible request

Use these as **routing hypotheses**. A signature lists questions worth asking, not automatic requirements or a claim that every risk exists. Always inspect the shipping code, user intent, relevant library/OS contracts and direct positive-path evidence.

| User request / cue | Hidden problem families | Questions that change the engineering decision |
|---|---|---|
| Automatically reconnect / remember me | Persistence, exact authenticated identity, lifecycle, security, network transitions | What identity/session is restored? Which read was actually challenged? Is a retry safe after an uncertain mutation? |
| Combine lists across servers/accounts | Cross-scope identity, selection authority, staleness, filters and UI density | Is every object keyed by environment and immutable object ID? Can a late response retarget actions? Is the list navigable at 200% text? |
| Add/import from picker/Share | Android grant lifetime, admission, storage ownership, dupes, failure | Was the result admitted before clearing the grant? Are source bytes retained? Does file creation imply completion? |
| Move/copy/export/archive files | Collision policy, provider capability, non-atomic writes, partial outcomes | Does Create New return a new object? Can a wrong URI truncate another? Is Move truly verified Copy plus deletion? |
| Resume downloads/transfers after restart | Distinct payload/metadata lifecycles, journal, restore, scaling | Is resume state bounded and durable? Are normal same-path payload semantics allowed? Does restoration preserve exact prior state? |
| Make the UI simple/intuitive | Mental models, conventional controls, visual density, accessibility, state truth | Which user action is most common? What's visible while scrolling? Are controls genuinely reachable on tiny screens? |
| Add a fancy selector/navigation control | Domain ontology, selection meaning, discoverability, apply boundary | What set is traversed? What is selected, what happens on selection, how is applied change confirmed? |
| Show progress / say completed | End-to-end evidence, async status, user goals | Does accepted mean started, persisted, copied, independently verified, or actually usable? |
| Auto organize / clean up files | Ownership, accidental loss, classification uncertainty, provider mutation | What counts as safe to delete? What sources reference the file? What is a false positive cost? |
| Search / relate media intelligently | Entity resolution, data provenance, typed knowledge links | Which precise relationship is supported: shared creator, topic, franchise or speculation? Which metadata fields are absent vs unexplored? |
| Make it fast / reduce CI costs | Measurement, bottleneck, causal attribution, test coverage | Which resource dominates real elapsed time? Will a filter hide renames? Are tests *executed*? |
| Make feature safe / impossible to overwrite | User intent, scope, threat model, standard library semantics | Is same-name replacement ordinary authorized behavior? What unrelated object is demonstrably at risk? |
| Migrate/install update | Stable signer, versioned schema, private data, provider versions | Can a new APK install over populated old APK without data loss? Are temporary test signatures mistaken for release identity? |
| Works on emulator, fails on device | Physical OS/provider differences, harness, source code, permissions | What was last reached? Was system picker displayed? Was a file independently read back? |
| Find missing features or unexplored states | Incomplete inventory, synonyms, weak ontology, selection bias, unqualified novelty | Is it missing from map, source corpus, sampled products or actual use? Has the obvious counterexample search been done? |
| Automate a chain of actions | Side-effect uncertainty, idempotence, exact identity, recovery | Which writes can have happened before timeout? Can result be independently reconciled before retry? |
| Cross-app/exported context | Android intents, authorization, privacy, source/version matching | Does grant scope match exact attachment? Does the receiving app know the installed version? Does returned content need validation? |

Recognize *combinations*: a “one-tap Copy to another server” involves identity + write authority + asynchronous state + recoverability + UX and authenticated transport. A simple screenshot can hide multiple engineering disciplines.

## Anti-overdiagnosis

A complexity signature is not a diagnosis. The agent must explicitly check whether the relevant risk is **reachable and consequential**. Do not create new P0 blockers for hypothetical states with no executable path. Prefer the already shipped platform primitive and test a real positive path first, then its most plausible harmful counterexample. A normal label or icon fix should not trigger research on all signatures.
