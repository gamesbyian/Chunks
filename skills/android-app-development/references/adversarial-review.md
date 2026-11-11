# Independent adversarial PR review: find real defects without inventing contracts

Start from the actual old/new source and user-authorized operation. Review at the exact head, with current project requirements and one live incident or demonstrable test. Do **not** infer data loss merely because a common authorized write replaces an existing regular file.

## Review in this order

1. **Product path:** Is the code reachable from the shipping screen/intent/service? If only a dormant test helper, classify as research and locate the missing production caller.
2. **Authority:** Is principal, endpoint, source, destination, grant, permission, item/version and owner frozen and revalidated at mutation? Could a current editable field retarget the command after suspend?
3. **Positive outcome:** Can ordinary user intent succeed? Does the PR pass through platform-provider or protocol boundary, and independently read back exact bytes/state?
4. **Irreversible effect:** List boundaries where cancellation, timeout, process death, disk-full, 401, stale server or provider can leave an effect. Does status distinguish verified from uncertain? Is cleanup limited to proven ownership?
5. **Adversarial counterexample:** Try unrelated file, same-name folder, wrong returned URI, stale UI/OS grant, symlink/escape, account-scoped collision, duplicate confirmation, simultaneous caller and malformed metadata. Choose ones *reachable under the operation*, not an arbitrary collection of imagined invariants.
6. **Android reality:** Does JVM/Robolectric approximate OS behavior incorrectly (hard-link SELinux, picker windows, Android FGS)? Is actual relevant API-level emulator/device proof available?
7. **Evidence/CI:** Does a required workflow really dispatch on the changed paths, including renames and config? Count executed tests and check exact PR head, data fixtures, failure class, artifacts and trustworthy runner source.
8. **Scope/cost:** Is the change the smallest effective correction? Does it create a second model or unbounded generic safety engine? Would a conventional API solve it more directly?

## Review finding format

`Severity | observed/reproducible risk | exact code+source SHA | affected authorized scenario | expected vs actual | smallest fix | positive test | hostile/interrupt test | evidence tier | what remains unverified`.

Severity: P0 reachable unintended data loss/account-crossing/credential exposure; P1 core user flow broken or misleading success; P2 maintainability/performance/test gap with evidence; P3 optional/cosmetic or hypothesis. **Capability-local stop** when an unsafe optional operation is already disabled. Do not use a P0 label merely because a theoretically possible race is rhetorically alarming.

## Review counterexamples

**WRONG:** blanket forbid all same-name torrent payloads because the source file could already exist; equate generic `renameTo` with no-replace; call public RPC listing authenticated; treat a green unit test as a device test; assume picker launches mean Copy bytes arrived; automatically delete stale-looking app-private files.

**RIGHT:** distinguish exact user authorization, ownership, platform provider and actual effect; require accepted positive path, source retention, readback, durable uncertainty and independent tests. Retire outdated requirements explicitly but keep true safety protections.

If no actionable defect is found, say so. A correct review may legitimately conclude “no issue found; these platform tiers remain untested.”

## Local PR-description safety check

Before posting a partial PR, run `python3 scripts/lint_pr_body.py /path/to/pr-body.md` from the skill root. GitHub's parser can interpret negated closing keywords as real issue-closing directives (Glimpse PR #166/#168). For unfinished work use **"Issue #143 remains open"**, without a closing verb adjacent to the issue number. The CLI identifies a subset of dangerous patterns; after merge, inspect the actual issue state. Genuine intentional `Closes #143` remains appropriate only for independently completed acceptance.
