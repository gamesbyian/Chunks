# Manual cleanup of superseded automatic Chunks APK artifacts

This is an intentionally narrow maintenance workflow. The read-only account census on November 2 found **720** retained artifacts named `chunks-debug-prealpha`, about **16.86 GiB** in total. **Two different workflows use the same artifact name:** the routine `android-check.yml` build and the explicitly dispatched `publish-debug-apk.yml` owner-testing publication. A name-only deletion would be dangerous.

[Manual safe Chunks auto-APK artifact cleanup](../.github/workflows/manual-auto-apk-artifact-cleanup.yml) only considers **verified completed** automatic build artifacts. It never touches `publish-debug-apk.yml`, the versioned release workflow, emulator acceptance outputs, source data, ROM/original research, or unrelated repositories.

## Review-first execution

1. In the GitHub **Actions** tab, select **Manual safe Chunks auto-APK artifact cleanup**. Choose **Run workflow**, `main`, and `mode=plan`. This uses read-only permission and executes the offline negative tests first.
2. Read its job summary and download `chunks-auto-apk-cleanup-plan` (JSON, retained 3 days). Inspect each proposed artifact ID, exact producing run, source SHA, timestamp, size, and digest. The plan is limited to **20 artifacts**.
3. If every deletion is appropriate, launch a **new manual run**, `mode=apply`, copy the exact `candidate_sha256` from the inspected plan, and enter the confirmation `DELETE-CHUNKS-AUTO-APK-ARTIFACTS`. Missing or changed candidate identity refuses the entire batch **before deletion**.
4. If execution succeeds, read the verified receipt. Each deletion requires the GitHub REST API to return **HTTP 204**, followed by an independent **HTTP 404** absence check. Partial receipts remain in workflow logs on any later error; a failed job must never be recorded as a fully verified batch.

### Permanent safeguards

- The candidate must be at least **30 days old**; the **50 newest named artifacts** are unconditionally protected regardless of producer. 
- Only the full run path `.github/workflows/android-check.yml` qualifies. The similarly named owner-publication artifact is never eligible.
- Any source SHA currently at an open PR head is protected; an incomplete, malformed, truncated, or changing artifact page refuses the scan. Each candidate is read again and its exact ID/name/byte count/time/source/digest/run is checked before deletion.
- A manual run is required for each maximum 20-item batch; there is **no cron or push cleanup** and no auto-delete on an unreviewed plan.
- Do not assume an older artifact is unreferenced merely because it is a routine build. Before approving a batch, cross-check any issue/PR evidence links and nominated owner-device APKs. If uncertain, do not run `apply`.

GitHub's artifact deletion endpoint requires `actions:write`. The privileged job runs only on explicit manual dispatch against `main`, after a successful read-only planning job. Pull-request builds execute **only offline contract tests**; they have no Actions write token. The workflow does not access protected hosts, run torrent operations, or use credentials for external servers.

GitHub storage billing accrues hourly. Public repositories' standard Actions use is free, and retained compressed artifact bytes do not directly prove billed private-repository storage. Removing old artifacts may reduce retained object count and future accumulation, but cannot reverse accrued billing usage.
