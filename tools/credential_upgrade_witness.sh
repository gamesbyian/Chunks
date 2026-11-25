#!/usr/bin/env bash
# In-place upgrade witness for Keystore-sealed credentials (A2611-05/A2611-09).
# Usage: credential_upgrade_witness.sh <base-app.apk> <candidate-app.apk> <candidate-test.apk> <out-dir>
# Seeds records with the base APK, upgrades in place (data and Keystore kept)
# and verifies with the candidate. Each phase must execute exactly one passing
# test; an assumption skip or a missing result is a failure, never a pass.
set -euo pipefail
base="$1"; candidate="$2"; tests="$3"; out="$4"
mkdir -p "$out"
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"

adb wait-for-device
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb uninstall "$pkg.test" >/dev/null 2>&1 || true
adb install "$base"
adb install -t "$tests"
adb shell dumpsys package "$pkg" | grep -m1 versionCode | tee "$out/base-version.txt"

phase() {
  local name="$1" class="$2"
  adb shell am instrument -w -r -e chunksUpgradeWitness "$name" -e class "$pkg.$class" "$runner" \
    > "$out/$name.txt" 2>&1 || true
  # Exactly one executed, passing test; reject failures, errors and assumption skips.
  if ! grep -q "^OK (1 test)" "$out/$name.txt" \
      || grep -q "INSTRUMENTATION_STATUS_CODE: -[1-4]" "$out/$name.txt"; then
    echo "::error::credential upgrade witness phase '$name' did not execute and pass exactly one test"
    tail -n 40 "$out/$name.txt"
    exit 1
  fi
  echo "CHUNKS_UPGRADE_WITNESS: phase=$name passed"
}

phase seed CredentialUpgradeSeedInstrumentedTest
# In-place upgrade: same package and signing key, higher versionCode, no uninstall.
adb install -r "$candidate"
adb shell dumpsys package "$pkg" | grep -m1 versionCode | tee "$out/candidate-version.txt"
phase verify CredentialUpgradeVerifyInstrumentedTest
