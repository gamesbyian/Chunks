# Derive executable acceptance oracles from user and system contracts

**Trigger:** a feature, repair or specification claims a meaningful result without an independent observation. **Exclude:** an already covered, deterministic microchange whose focused test directly proves the outcome. **Inputs:** user-outcome requirement, actual shipping entrypoint, exact resource identity, platform/provider, source head and available environment. **Output:** bounded positive and disconfirming acceptance cases with truthful evidence tier.

## V1. Atomize the claimed result

Write one assertion per independently falsifiable outcome: user starts action; OS accepts input; command is attempted; service receives it; durable state persists; affected target matches; user sees verified feedback. A click or successful compilation is not equivalent to persisted bytes or usable result.

## V2. Select the observation boundary

Draw input → actual production call path → relevant platform/API/provider → authoritative state/bytes → feedback. Choose the furthest boundary essential to the claim. A local unit test is sufficient for a pure parser; an rTorrent write needs authenticated exact-account reconciliation; a photo annotation may need an installed device; a human preference needs situated user evidence.

## V3. Construct minimal representative fixtures

Specify initial state and immutable IDs (including before/after bytes or digest if relevant), allowed actor, source data, selected destination, permissions, environment/API version, and the one change being exercised. Avoid already-completed fixtures that make a test pass without a new operation. Use disposable data and authorized accounts for potentially destructive paths.

## V4. Generate positive and disconfirming cases

Positive: GIVEN actual permitted initial state, WHEN user completes the real entrypoint, THEN independent readback proves intended postcondition and original data invariants. Negative: invert the most decision-relevant reachable boundary, such as revoked grant, wrong returned URI, stale account, timeout-after-write, process death, malformed external input or unreachable control. Show expected truthful recovery or UNCERTAIN state; do not silently retry irreversible commands.

## V5. Instrument and execute economically

Prefer smallest test that reaches the necessary boundary and preserves normal setup. If several features share expensive emulator/provider boot, batch setup while retaining per-item fixture and assertion IDs. Capture source SHA, APK/artifact hash, environment, executed assertion counts, first failure and last reached state. Classify red as product, test harness, environment or unknown without erasing original evidence.

## V6. Separate outcome tiers and stop

Mark each case PLANNED, EXECUTED-PASS, EXECUTED-FAIL, NOT REACHED, SKIPPED or INACCESSIBLE, with applicable E0–E5 evidence tier from [Verification and CI](verification-and-ci.md). A green synthetic path cannot qualify an unobserved real provider or owner-visible UI. Stop after each consequential claim has correct-tier evidence or a named missing proof; run a whole release matrix only when changed scope and project policy require it.

**Independent oracle:** read back exact authoritative target/bytes/state from outside the mutation caller. A test that consults the command's own optimistic return value is not independent. **Negative controls:** picker launch without file receipt, a skipped test suite, prior-head green, a mocked RPC acknowledgement, a photo output whose unchanged regions were not compared, and a deliberately precompleted transfer.
