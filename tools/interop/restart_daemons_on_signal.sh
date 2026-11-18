#!/usr/bin/env bash
# Manual rig for ClientReconnectInteropTest: after the test writes $1/ready, restart
# each real loopback daemon with its exact original command line (state kept), then
# touch $1/restarted. Usage: tools/interop/restart_daemons_on_signal.sh <signal dir> &
S="$1"; ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
until [ -f "$S/ready" ]; do sleep 1; done
for pat in transmission-daemon qbittorrent-nox "deluged -c" "rtorrent -n"; do
  line=$(pgrep -af "$pat" | grep -v pgrep | head -1); pid=${line%% *}; cmd=${line#* }
  [ -n "$pid" ] || continue
  kill "$pid"; while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
  echo "$cmd" >> "$S/restarted.log"
  (cd "$ROOT" && nohup $cmd > "$S/$(echo $pat | tr ' ' _).log" 2>&1 < /dev/null &)
done
sleep 8; touch "$S/restarted"
