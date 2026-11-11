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
  local pkg
  for pkg in "$@"; do dpkg -s "$pkg" >/dev/null 2>&1 || return 1; done
}

if apt_update && apt_install "$@" && all_installed "$@"; then exit 0; fi

echo "APT_FALLBACK: default mirror failed; retrying against archive.ubuntu.com" >&2
shopt -s nullglob
for source_file in "$SOURCES_DIR"/sources.list "$SOURCES_DIR"/sources.list.d/*.sources "$SOURCES_DIR"/sources.list.d/*.list; do
  [ -f "$source_file" ] && sudo sed -i -E \
    's#https?://azure\.archive\.ubuntu\.com/ubuntu/?#http://archive.ubuntu.com/ubuntu/#g' "$source_file"
done
if apt_update && apt_install "$@" && all_installed "$@"; then
  echo "APT_FALLBACK: installed via archive.ubuntu.com" >&2
  exit 0
fi
echo "APT_FALLBACK: packages not installed after retries and mirror fallback: $*" >&2
exit 1
