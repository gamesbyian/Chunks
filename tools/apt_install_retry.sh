#!/usr/bin/env bash
# Bounded apt install for CI runners (usage: apt_install_retry.sh PACKAGE...).
#
# GitHub-hosted Ubuntu runners occasionally cannot reach their default (Azure) mirror;
# `apt-get update` then hangs until an outer timeout kills the job before any test starts
# (observed 2026-11-11: exit 124 for over two hours, also on plain main). This installs with
# short per-request timeouts and retries, and only if that fails rewrites the Azure mirror to the
# primary Ubuntu archive and tries once more. It verifies every requested package is installed and
# otherwise exits non-zero, so a missing dependency is never reported as success.
set -u
[ "$#" -ge 1 ] || { echo "usage: apt_install_retry.sh PACKAGE..." >&2; exit 2; }
SOURCES_DIR="${APT_SOURCES_DIR:-/etc/apt}"
OPTS=(-o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20)

apt_update()  { sudo timeout 150s apt-get update -qq "${OPTS[@]}"; }
apt_install() { sudo timeout 240s env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${OPTS[@]}" "$@"; }
all_installed() {
  local pkg status
  for pkg in "$@"; do
    # dpkg -s also succeeds for some unpacked/config-files states.
    status=$(dpkg-query -W -f='${db:Status-Status} ${db:Status-Eflag}' "$pkg" 2>/dev/null) || return 1
    [ "$status" = "installed ok" ] || return 1
  done
}

install_checked() {
  local status=0 audit
  apt_install "$@" || status=$?
  echo "APT_INSTALL: exit=$status" >&2
  if [ "$status" = 0 ]; then
    all_installed "$@"
    return $?
  fi
  # Timeout can land after dpkg configured all requested packages but before
  # apt finishes its final housekeeping. Do not repeat a completed install;
  # accept only the bounded, independently verified clean state.
  [ "$status" = 124 ] || return 1
  all_installed "$@" || return 1
  audit=$(sudo timeout 30s dpkg --audit 2>&1) || return 1
  [ -z "$audit" ] || return 1
  sudo timeout 30s apt-get check -qq "${OPTS[@]}" || return 1
  echo "APT_INSTALL: timed out; exact configured packages, clean dpkg audit and dependency check independently verified" >&2
}

if apt_update && install_checked "$@"; then exit 0; fi

echo "APT_FALLBACK: default mirror failed; retrying against archive.ubuntu.com" >&2
shopt -s nullglob
for source_file in "$SOURCES_DIR"/sources.list "$SOURCES_DIR"/sources.list.d/*.sources "$SOURCES_DIR"/sources.list.d/*.list; do
  [ -f "$source_file" ] && sudo sed -i -E \
    's#https?://azure\.archive\.ubuntu\.com/ubuntu/?#http://archive.ubuntu.com/ubuntu/#g' "$source_file"
done
if apt_update && install_checked "$@"; then
  echo "APT_FALLBACK: installed via archive.ubuntu.com" >&2
  exit 0
fi
echo "APT_FALLBACK: packages not installed after retries and mirror fallback: $*" >&2
exit 1
