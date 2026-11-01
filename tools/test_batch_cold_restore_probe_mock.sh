#!/usr/bin/env bash
# Fake-adb negative controls; no Android device contacted.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/state"
cat > "$tmp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -eo pipefail
case "$1" in
  shell)
    shift
    case "$1" in
      getprop)
        if [[ "$2" == ro.kernel.qemu ]]; then
          [[ "$MOCK_PHYSICAL" == 1 ]] && echo 0 || echo 1
        elif [[ "$2" == ro.build.version.sdk ]]; then
          [[ "$MOCK_WRONG_API" == 1 ]] && echo 35 || echo 29
        else exit 95; fi
        ;;
      am)
        if [[ "$2" == force-stop ]]; then
          echo stop >> "$MOCK_DIR/events"
          [[ "$MOCK_STOP_FAIL" != 1 ]] || exit 34
        elif [[ "$2" == instrument ]]; then
          phase=unknown
          case "$*" in
            *phaseA_publishOneThenPersistStoppedBatchBeforeForceStop*) phase=a ;;
            *phaseB_coldProcessRecoveryShowsWarningWithoutCreatingAnotherRow*) phase=b ;;
          esac
          [[ "$phase" != unknown ]] || exit 35
          run_id=""
          previous=""
          for token in "$@"; do
            if [[ "$previous" == chunksBatchRunId ]]; then run_id="$token"; break; fi
            previous="$token"
          done
          [[ "$run_id" =~ ^[0-9a-f]{16}$ ]] || exit 42
          if [[ "$phase" == a ]]; then
            printf '%s' "$run_id" > "$MOCK_DIR/run_id"
          else
            [[ "$run_id" == "$(cat "$MOCK_DIR/run_id")" ]] || exit 43
          fi
          printf '%s\n' "$phase" > "$MOCK_DIR/phase"
          echo "instrument-$phase" >> "$MOCK_DIR/events"
          [[ "$MOCK_INSTRUMENT_FAIL" != "$phase" ]] || exit 36
          if [[ "$MOCK_SKIP" == "$phase" ]]; then
            echo 'INSTRUMENTATION_STATUS_CODE: -3'
          fi
          if [[ "$MOCK_FAIL" == "$phase" ]]; then
            echo 'FAILURES!!!'
          fi
          echo 'OK (1 test)'
          if [[ "$MOCK_DUPLICATE" == "$phase" ]]; then
            echo 'OK (1 test)'
          fi
          echo 'INSTRUMENTATION_CODE: -1'
        else exit 37; fi
        ;;
      *) exit 38 ;;
    esac
    ;;
  logcat)
    if [[ "$2" == -c ]]; then exit 0; fi
    if [[ "$2" == -d ]]; then
      phase="$(cat "$MOCK_DIR/phase" 2>/dev/null || true)"
      [[ "$MOCK_NO_MARKER" != "$phase" ]] || exit 0
      case "$phase" in
        a) echo 'CHUNKS_BATCH_COLD phase=a partial=true verified=1 original=true' ;;
        b) echo 'CHUNKS_BATCH_COLD phase=b cold=true warning=true original=true no_duplicate=true' ;;
        *) exit 39 ;;
      esac
      exit 0
    fi
    exit 40
    ;;
  *) exit 41 ;;
esac
ADB_MOCK
chmod +x "$tmp/bin/adb"
export MOCK_DIR="$tmp/state" PATH="$tmp/bin:$PATH"
bash -n "$root/batch_cold_restore_probe.sh"
bash -n "$root/test_batch_cold_restore_probe_mock.sh"
reset() {
  : > "$MOCK_DIR/events"
  rm -f "$MOCK_DIR/phase" "$MOCK_DIR/run_id"
}
good() {
  bash "$root/batch_cold_restore_probe.sh" 29 > "$tmp/yes" 2>&1
  grep -Fq 'CHUNKS_BATCH_COLD=PASS api=29' "$tmp/yes"
  [[ "$(grep -c '^instrument-' "$MOCK_DIR/events")" == 2 ]]
  [[ "$(grep -c '^stop$' "$MOCK_DIR/events")" == 3 ]]
}
bad() {
  if "$@" bash "$root/batch_cold_restore_probe.sh" 29 > "$tmp/no" 2>&1; then
    echo "CHUNKS_BATCH_COLD_MOCK: false success for $*" >&2
    exit 1
  fi
}
reset; good
first_run="$(cat "$MOCK_DIR/run_id")"
reset; good
[[ "$first_run" != "$(cat "$MOCK_DIR/run_id")" ]] || {
  echo "CHUNKS_BATCH_COLD_MOCK: separate runs reused a fixture identity" >&2; exit 1;
}
reset; bad env MOCK_PHYSICAL=1
[[ ! -s "$MOCK_DIR/events" ]]
reset; bad env MOCK_WRONG_API=1
[[ ! -s "$MOCK_DIR/events" ]]
reset; bad env MOCK_INSTRUMENT_FAIL=a
reset; bad env MOCK_SKIP=a
reset; bad env MOCK_DUPLICATE=a
reset; bad env MOCK_NO_MARKER=a
reset; bad env MOCK_STOP_FAIL=1
reset; bad env MOCK_FAIL=b
reset; bad env MOCK_SKIP=b
reset; bad env MOCK_NO_MARKER=b
echo 'CHUNKS_BATCH_COLD_MOCK: positive and unsafe/false/skipped/missing receipts checked'
