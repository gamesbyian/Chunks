#!/usr/bin/env bash
# Offline false-positive controls; these do not run Android, JNI or real peers.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/state"
cat > "$tmp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${BAD:-}" != second_device ]] || printf 'SECOND\tdevice\n'
    [[ "${BAD:-}" != devices_failure ]] || exit 65
    ;;
  get-state)
    printf 'device\n'
    [[ "${BAD:-}" != state_failure ]] || exit 66
    ;;
  shell)
    shift
    case "$*" in
      'getprop ro.kernel.qemu')
        if [[ "${BAD:-}" == physical ]]; then echo 0
        elif [[ "${BAD:-}" == ambiguous_qemu ]]; then echo unknown
        else echo 1; fi
        [[ "${BAD:-}" != identity_failure ]] || exit 67
        ;;
      'getprop ro.build.version.sdk')
        [[ "${BAD:-}" != old_api ]] && echo 35 || echo 28
        [[ "${BAD:-}" != sdk_failure ]] || exit 68
        ;;
      'if pidof '* ) [[ "${BAD:-}" == alive ]] && echo running || echo stopped ;;
      'am force-stop '* )
        echo stop >> "$MOCK_STATE/stops"
        [[ "${BAD:-}" != stop_failure ]] || exit 24
        ;;
      'am instrument '* )
        kind=share
        [[ "$*" != *'chunksIntakeKind magnet'* ]] || kind=magnet
        printf '%s' "$kind" > "$MOCK_STATE/kind"
        echo instrument >> "$MOCK_STATE/instruments"
        if [[ "$*" == *'#phaseDelete_'* ]]; then
          echo delete > "$MOCK_STATE/phase"
          [[ "${BAD:-}" == delete_fail ]] && printf 'OK (1 test)\nFAILURES!!!\n' || printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
        elif [[ "$*" == *'#phaseA_'* ]]; then
          echo a > "$MOCK_STATE/phase"
          [[ "${BAD:-}" == no_death ]] && echo 'OK (1 test)' || echo 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.'
        else
          echo b > "$MOCK_STATE/phase"
          case "${BAD:-}" in
            false_ok) echo 'INSTRUMENTATION_RESULT: stream=OK (1 test)' ;;
            skip) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n' ;;
            fail) printf 'OK (1 test)\nFAILURES!!!\n' ;;
            duplicate_ok) printf 'OK (1 test)\nOK (1 test)\n' ;;
            command_fail) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'; exit 25 ;;
            terminal_missing) echo 'OK (1 test)' ;;
            terminal_failed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: 0\n' ;;
            terminal_duplicate) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n' ;;
            terminal_mixed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n' ;;
            *) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n' ;;
          esac
        fi
        ;;
      *) echo 'Unexpected fake adb shell command' >&2; exit 99 ;;
    esac
    ;;
  logcat)
    [[ "${2:-}" == -d ]] || exit 0
    [[ "${BAD:-}" != log_failure ]] || exit 22
    kind="$(cat "$MOCK_STATE/kind")"
    [[ "${BAD:-}" != wrong_kind ]] || kind=other
    digest="$(printf '%064d' 0)"
    if [[ "${BAD:-}" != no_arm ]]; then
      echo "CHUNKS_INTAKE kind=$kind armed reviewed=true bytes=524288 stopped=true sha256=$digest"
      [[ "${BAD:-}" != duplicate_arm ]] || echo "CHUNKS_INTAKE kind=$kind armed reviewed=true bytes=524288 stopped=true sha256=$digest"
    fi
    if [[ "$(cat "$MOCK_STATE/phase")" == delete && "${BAD:-}" != no_delete ]]; then
      echo "CHUNKS_INTAKE_DELETE verified=true payload_absent=true session_absent=true canary_intact=true bytes=524288"
    fi
    if [[ "$(cat "$MOCK_STATE/phase")" == b && "${BAD:-}" != no_recovery ]]; then
      [[ "${BAD:-}" != changed_bytes ]] || digest="$(printf '%064d' 1)"
      seeded=524288
      [[ "${BAD:-}" != no_seed ]] || seeded=0
      echo "CHUNKS_INTAKE kind=$kind recovered stopped=true bytes=524288 seeded_bytes=$seeded kept_files=true sha256=$digest"
      [[ "${BAD:-}" != duplicate_recovery ]] || echo "CHUNKS_INTAKE kind=$kind recovered stopped=true bytes=524288 seeded_bytes=$seeded kept_files=true sha256=$digest"
    fi
    ;;
  *) exit 98 ;;
esac
MOCK
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH" MOCK_STATE="$tmp/state"
bash -n "$root/phone_intake_journey.sh"
bash -n "$root/test_phone_intake_journey.sh"
env -u ADB_SERIAL -u ANDROID_SERIAL bash "$root/phone_intake_journey.sh" > "$tmp/positive.log"
[[ "$(wc -l < "$MOCK_STATE/instruments")" == 5 ]]
for bad in physical second_device devices_failure state_failure ambiguous_qemu identity_failure \
           sdk_failure old_api stop_failure alive no_death no_arm duplicate_arm log_failure wrong_kind \
           false_ok skip fail duplicate_ok command_fail terminal_missing terminal_failed \
           terminal_duplicate terminal_mixed no_recovery changed_bytes no_seed duplicate_recovery \
           no_delete delete_fail; do
  rm -f "$MOCK_STATE/stops" "$MOCK_STATE/instruments"
  if env -u ADB_SERIAL -u ANDROID_SERIAL BAD="$bad" bash "$root/phone_intake_journey.sh" > "$tmp/$bad.log" 2>&1; then
    echo "CHUNKS_INTAKE_MOCK: falsely accepted $bad" >&2; exit 1
  fi
  case "$bad" in
    physical|second_device|devices_failure|state_failure|ambiguous_qemu|identity_failure|sdk_failure|old_api)
      [[ ! -e "$MOCK_STATE/stops" && ! -e "$MOCK_STATE/instruments" ]] || {
        echo "CHUNKS_INTAKE_MOCK: mutated before unsafe ADB target refusal: $bad" >&2; exit 1;
      } ;;
  esac
done
rm -f "$MOCK_STATE/stops" "$MOCK_STATE/instruments"
if ADB_SERIAL=WRONG ANDROID_SERIAL=MOCK_USB bash "$root/phone_intake_journey.sh" > "$tmp/conflicting.log" 2>&1; then
  echo 'CHUNKS_INTAKE_MOCK: accepted conflicting ADB selectors' >&2; exit 1
fi
[[ ! -e "$MOCK_STATE/stops" && ! -e "$MOCK_STATE/instruments" ]] || {
  echo 'CHUNKS_INTAKE_MOCK: mutated before rejecting contradictory selectors' >&2; exit 1;
}
if ANDROID_SERIAL=NOT_CONNECTED bash "$root/phone_intake_journey.sh" > "$tmp/unknown.log" 2>&1; then
  echo 'CHUNKS_INTAKE_MOCK: accepted unlisted ADB selector' >&2; exit 1
fi
[[ ! -e "$MOCK_STATE/stops" && ! -e "$MOCK_STATE/instruments" ]] || {
  echo 'CHUNKS_INTAKE_MOCK: mutated before rejecting unlisted serial' >&2; exit 1;
}
echo 'CHUNKS_INTAKE_MOCK: exact ADB target guard, both routes and terminal false receipts rejected (offline only)'
