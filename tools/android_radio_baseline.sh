#!/usr/bin/env bash
# Functions sourced by opt-in physical/emulator radio fault-injection scripts.
# Never make a change when Android does not report an exact 0/1 baseline.
chunks_read_radio_baseline() {
  local wifi data
  wifi="$(adb shell settings get global wifi_on | tr -d '\r\n')" || return 1
  data="$(adb shell settings get global mobile_data | tr -d '\r\n')" || return 1
  if [[ ! "$wifi" =~ ^[01]$ || ! "$data" =~ ^[01]$ ]]; then
    echo "Cannot verify original Wi-Fi and mobile-data states; refusing radio changes" >&2
    return 1
  fi
  CHUNKS_WIFI_BASELINE="$wifi"
  CHUNKS_DATA_BASELINE="$data"
}

chunks_restore_radio_baseline() {
  [[ "${CHUNKS_WIFI_BASELINE:-}" =~ ^[01]$ && "${CHUNKS_DATA_BASELINE:-}" =~ ^[01]$ ]] || return 0
  local rc=0
  if [[ "${CHUNKS_WIFI_BASELINE:-}" == 1 ]]; then
    adb shell svc wifi enable >/dev/null 2>&1 || rc=1
  elif [[ "${CHUNKS_WIFI_BASELINE:-}" == 0 ]]; then
    adb shell svc wifi disable >/dev/null 2>&1 || rc=1
  fi
  if [[ "${CHUNKS_DATA_BASELINE:-}" == 1 ]]; then
    adb shell svc data enable >/dev/null 2>&1 || rc=1
  elif [[ "${CHUNKS_DATA_BASELINE:-}" == 0 ]]; then
    adb shell svc data disable >/dev/null 2>&1 || rc=1
  fi
  # svc success means only that Android accepted a request. Confirm the
  # observable settings returned to their original on/off values.
  local current_wifi current_data
  for _ in 1 2 3; do
    current_wifi="$(adb shell settings get global wifi_on | tr -d '\r\n')" || rc=1
    current_data="$(adb shell settings get global mobile_data | tr -d '\r\n')" || rc=1
    if [[ "$current_wifi" == "${CHUNKS_WIFI_BASELINE:-}" &&
          "$current_data" == "${CHUNKS_DATA_BASELINE:-}" ]]; then
      return "$rc"
    fi
    sleep 1
  done
  echo "Android radio state could not be read back at its original values" >&2
  return 1
}
