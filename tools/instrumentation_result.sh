# shellcheck shell=bash
# Sourced by installed-journey scripts. AndroidJUnitRunner prints "OK (N tests)"
# even when a test was skipped by an assumption (status -4) or ignored (-3);
# neither executed the journey's assertions, so neither is a pass.
instrumentation_passed() { # $1 raw `am instrument -r` output, $2 exact expected test count
  local raw=$1 count=$2
  tr -d '\r' < "$raw" | grep -qxE "OK \(${count} tests?\)" &&
    ! grep -qE '^INSTRUMENTATION_STATUS_CODE: -(3|4)' "$raw"
}
