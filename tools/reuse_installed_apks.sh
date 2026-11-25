#!/usr/bin/env bash
# Only for disposable installed debug APKs, never release or protected hosts.
set -euo pipefail
fail() { echo "::error::$1" >&2; exit 1; }
[[ "${APK_RUN_ID:-}" =~ ^[0-9]{1,20}$ ]] || fail "Invalid APK producer run ID"
test "$(git rev-parse HEAD)" = "${GITHUB_SHA:?}" || fail "Checkout source mismatch"

case "${1:-}" in
  verify-run)
    gh api "repos/${GITHUB_REPOSITORY:?}/actions/runs/$APK_RUN_ID" |
      jq -e --arg repo "$GITHUB_REPOSITORY" --arg sha "$GITHUB_SHA" '
        .head_repository.full_name == $repo and
        .head_sha == $sha and .event == "workflow_dispatch" and
        .path == ".github/workflows/installed-protocol-client.yml" and
        .status == "completed" and .conclusion == "success"' >/dev/null ||
      fail "APK producer is not a successful exact-source manual Installed Universal Add run"
    ;;
  stage)
    input="${RUNNER_TEMP:?}/shared-installed-apks"
    for file in source-sha source-tree SHA256SUMS app.apk test.apk; do
      [[ -f "$input/$file" && ! -L "$input/$file" && -s "$input/$file" ]] ||
        fail "Missing or invalid APK artifact member"
    done
    test "$(cat "$input/source-sha")" = "$GITHUB_SHA" || fail "APK source mismatch"
    test "$(cat "$input/source-tree")" = "$(git rev-parse HEAD^{tree})" || fail "APK tree mismatch"
    test "$(awk '{print $2}' "$input/SHA256SUMS")" = $'app.apk\ntest.apk' || fail "Unexpected APK checksum members"
    (cd "$input" && sha256sum --strict --check SHA256SUMS) || fail "APK checksum mismatch"
    mkdir -p app/build/outputs/apk/{debug,androidTest/debug}
    cp "$input/app.apk" app/build/outputs/apk/debug/app-debug.apk
    cp "$input/test.apk" app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    echo "CHUNKS_SHARED_INSTALLED_APK_REUSE: producer_run=$APK_RUN_ID source=$GITHUB_SHA build_skipped=true"
    ;;
  *) fail "Expected verify-run or stage" ;;
esac
