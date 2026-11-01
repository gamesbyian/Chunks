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
  shell)
    shift
    case "$*" in
      'getprop ro.kernel.qemu') [[ "${BAD:-}" == physical ]] && echo 0 || echo 1 ;;
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
        if [[ "$*" == *'#phaseA_'* ]]; then
          echo a > "$MOCK_STATE/phase"
          [[ "${BAD:-}" == no_death ]] && echo 'OK (1 test)' || echo 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.'
        else
          echo b > "$MOCK_STATE/phase"
          case "${BAD:-}" in
            false_ok) echo 'INSTRUMENTATION_RESULT: stream=OK (1 test)' ;;
            skip) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n' ;;
            fail) printf 'OK (1 test)\nFAILURES!!!\n' ;;
            duplicate_ok) printf 'OK (1 test)\nOK (1 test)\n' ;;
            command_fail) echo 'OK (1 test)'; exit 25 ;;
            *) echo 'OK (1 test)' ;;
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
bash "$root/phone_intake_journey.sh" > "$tmp/positive.log"
[[ "$(wc -l < "$MOCK_STATE/instruments")" == 4 ]]
for bad in physical stop_failure alive no_death no_arm duplicate_arm log_failure wrong_kind \
           false_ok skip fail duplicate_ok command_fail no_recovery changed_bytes no_seed duplicate_recovery; do
  rm -f "$MOCK_STATE/stops" "$MOCK_STATE/instruments"
  if BAD="$bad" bash "$root/phone_intake_journey.sh" > "$tmp/$bad.log" 2>&1; then
    echo "CHUNKS_INTAKE_MOCK: falsely accepted $bad" >&2; exit 1
  fi
  if [[ "$bad" == physical && -e "$MOCK_STATE/stops" ]]; then
    echo 'CHUNKS_INTAKE_MOCK: attempted to stop a physical app' >&2; exit 1
  fi
done
echo 'CHUNKS_INTAKE_MOCK: both entry kinds accepted; 17 false receipts/unsafe targets refused (offline only)'
