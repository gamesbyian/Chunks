#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/android_radio_baseline.sh"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
adb() {
  case "$*" in
    'shell settings get global wifi_on') printf '%s\n' "${TEST_WIFI:-1}" ;;
    'shell settings get global mobile_data') printf '%s\n' "${TEST_DATA:-0}" ;;
    'shell svc wifi enable'|'shell svc wifi disable'|'shell svc data enable'|'shell svc data disable') printf '%s\n' "$*" >> "$log" ;;
    *) echo "Unknown fake adb arguments $*" >&2; return 1 ;;
  esac
}
CHUNKS_WIFI_BASELINE= CHUNKS_DATA_BASELINE=
TEST_WIFI=1 TEST_DATA=0
chunks_read_radio_baseline
[[ "$CHUNKS_WIFI_BASELINE" == 1 && "$CHUNKS_DATA_BASELINE" == 0 ]]
chunks_restore_radio_baseline
[[ "$(cat "$log")" == $'shell svc wifi enable\nshell svc data disable' ]]
: > "$log"
CHUNKS_WIFI_BASELINE= CHUNKS_DATA_BASELINE=
TEST_WIFI=0 TEST_DATA=1
chunks_read_radio_baseline
chunks_restore_radio_baseline
[[ "$(cat "$log")" == $'shell svc wifi disable\nshell svc data enable' ]]
: > "$log"
CHUNKS_WIFI_BASELINE= CHUNKS_DATA_BASELINE=
if TEST_WIFI=null TEST_DATA=1 chunks_read_radio_baseline >/dev/null 2>&1; then echo "Accepted unknown Wi-Fi baseline" >&2; exit 1; fi
[[ -z "$CHUNKS_WIFI_BASELINE" && -z "$CHUNKS_DATA_BASELINE" ]]
chunks_restore_radio_baseline
[[ ! -s "$log" ]]
if TEST_WIFI=1 TEST_DATA=null chunks_read_radio_baseline >/dev/null 2>&1; then echo "Accepted unknown data baseline" >&2; exit 1; fi
[[ ! -s "$log" ]]
echo 'CHUNKS_RADIO_BASELINE_TEST: restore exact on/off states, refuse unknown baseline without mutations'
