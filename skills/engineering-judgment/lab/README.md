# Executable judgment lab v0.1

These are five **synthetic, standard-library Python engineering tasks**, with deliberately failing starting code and independently runnable behavioral tests. Their purpose is to check whether an agent can *implement* a relevant distinction, not merely select the right multiple-choice answer. They are **not** evidence of Android, SAF, protected host, network or production-app functionality.

## The five deliberately different problems

| Task | Key failure or negative control |
|---|---|
| `authenticated-profile` | A public server-version response does not prove supplied account identity; failed attempts preserve saved credentials |
| `immutable-publication` | Exclusive creation may still expose partial bytes; ensure private POSIX target only receives complete object and unknown siblings survive |
| `uncertain-command` | A timeout after a possibly applied irreversible write cannot trigger blind replay; verify exact transaction |
| `rename-aware-ci` | A CI selector must consider both path identities, and unknown input must not silently skip expensive but relevant tests |
| `small-label-fix` | A low-stakes reversible copy change must not require elaborate architecture; assess unnecessary complexity separately |

The [manifest](manifest.json) enumerates task IDs. A task bundle contains **only `task.md` and `candidate.py`**; no solution or oracle. Each task has a repository-local `reference.py` and `test_contract.py` for an internal **two-sided gate**: the unmodified baseline must FAIL an executed test and the reference must PASS the same tests. This is the validation pattern used in modern coding-benchmark audits, but does not remove the possibility of an incorrect oracle.

## Commands

From `skills/engineering-judgment/`:

```sh
python3 scripts/run_lab.py --selfcheck
python3 scripts/run_lab.py --export authenticated-profile --output /tmp/auth-task-new
# Give ONLY /tmp/auth-task-new to a trial agent.
# The agent edits candidate.py in that directory.

# Only inside an externally enforced disposable sandbox without network, secrets or host mounts:
python3 scripts/run_lab.py --evaluate authenticated-profile --candidate /tmp/auth-task-new/candidate.py --permit-untrusted-code
```

`--evaluate` executes candidate Python and has **no OS sandbox**, notwithstanding its temporary test directory, limited environment or timeout. It is unsafe to evaluate untrusted agent code in a privileged workspace or on the owner's normal machine. The included CI performs **only `--selfcheck` with trusted built-in files**, never external PR-submitted trial packages. Do not install code or grant credentials during evaluation.

## Correct research protocol

A serious study uses the same model/tool/runtime, matched budgets and randomized task assignment across **skill** and **control** arms. Export individual bundles into separate, no-repository-access environments. Do not expose reference or oracle files, previous answers, project history or GitHub connectors to a supposedly blind candidate. Capture actual timing, token/tool/CI cost and patch artifacts in addition to correctness.

Review test results and patches independently. The reference patch can be **too narrow or wrong**. For example, the POSIX fixture intentionally does not assert cross-reboot directory durability or model a remote SAF provider. The semantic contract, not a particular implementation, governs acceptance. Inspect safe alternative solutions rather than rejecting them merely for code differences.

A successful run of five tiny tasks would be *preliminary* evidence about toy engineering transfer. Actual value requires prospective, real, externally reviewed work in Chunks, Glimpse or Expository with exact-head integrated acceptance, including a comparison arm and false-positive overengineering penalties.

## Evaluation quality checklist

- Does the task give all information a competent agent needs to infer the expected behavior?
- Does the untouched implementation **fail** for a meaningful user-facing reason?
- Does an independently reviewed valid implementation **pass**?
- Do tests inspect the observable postcondition instead of an implementation-specific code pattern?
- Can a deliberately wrong but superficially safe implementation still pass? Search for counterexamples.
- Is the task solvable within its allocated tooling and runtime?
- Could an agent see oracle/reference code or a matching commit through its connected sources?
- Have claims been restricted to the task's actual system and execution tier?

Benchmark design warnings: [OpenAI's 2026 coding-evaluation audit](https://openai.com/index/separating-signal-from-noise-coding-evaluations/) and [SWE-bench Pro V2 corrections](https://labs.scale.com/leaderboard/swe_bench_pro_public_v2). Neither validates our toy cases. One earlier benchmark revision needed substantial audits for misleading prompts and false-positive/negative tests; independent scenario review remains necessary.

## Pairwise measurement

Use the separate [matched-trial protocol and receipt format](trials.md) and [comparison script](../scripts/compare_trials.py) after running **two isolated agents under matched conditions**. It refuses to grant success based on skipped tests, answer leakage or pending independent review. The built-in task fixtures and reference code are in the same private repository, so simply pointing a GitHub-connected agent at the source is **not** a blind test.
