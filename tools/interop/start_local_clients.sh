#!/usr/bin/env bash
# Start real, loopback-only Transmission, qBittorrent and Deluge daemons for
# Chunks' RealClientInteropTest. Disposable state under $1 (default ./build/interop).
# Prints nothing secret: the credentials are throwaway fixtures for 127.0.0.1.
set -euo pipefail
ROOT="$(realpath -m "${1:-build/interop}")"
mkdir -p "$ROOT"/{tr,qb,de,dl/tr,dl/qb,dl/de}

# Deluge's distro packages target the distro Python; prefer one that imports.
PY=""
for candidate in /usr/bin/python3 /usr/bin/python3.12 /usr/bin/python3.11; do
  if [ -x "$candidate" ] && "$candidate" -c 'import deluge.core.daemon_entry, libtorrent' >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done

cat > "$ROOT/tr/settings.json" <<JSON
{"rpc-enabled":true,"rpc-port":19091,"rpc-bind-address":"127.0.0.1",
 "rpc-authentication-required":true,"rpc-username":"chunks","rpc-password":"chunkspass",
 "rpc-whitelist-enabled":false,"rpc-host-whitelist-enabled":false,
 "download-dir":"$ROOT/dl/tr","peer-port":51413,"dht-enabled":false,"lpd-enabled":false,
 "pex-enabled":false,"port-forwarding-enabled":false}
JSON
transmission-daemon -g "$ROOT/tr" -f >"$ROOT/tr.log" 2>&1 &

python3 - "$ROOT" <<'PYCONF'
import base64, hashlib, os, sys
root = sys.argv[1]
salt = os.urandom(16)
digest = hashlib.pbkdf2_hmac("sha512", b"chunkspass", salt, 100000, 64)
os.makedirs(f"{root}/qb/qBittorrent/config", exist_ok=True)
with open(f"{root}/qb/qBittorrent/config/qBittorrent.conf", "w") as f:
    f.write(f"""[LegalNotice]
Accepted=true
[BitTorrent]
Session\\DefaultSavePath={root}/dl/qb
Session\\Port=51414
Session\\DHTEnabled=false
[Preferences]
WebUI\\Port=18080
WebUI\\Address=127.0.0.1
WebUI\\Username=chunks
WebUI\\Password_PBKDF2="@ByteArray({base64.b64encode(salt).decode()}:{base64.b64encode(digest).decode()})"
WebUI\\CSRFProtection=true
WebUI\\HostHeaderValidation=false
""")
PYCONF
qbittorrent-nox --profile="$ROOT/qb" >"$ROOT/qb.log" 2>&1 &

if [ -n "$PY" ]; then
  echo "localclient:localpass:10" > "$ROOT/de/auth"
  "$PY" /usr/bin/deluged -c "$ROOT/de" -p 58846 -L warning -l "$ROOT/de.log"
  sleep 3
  "$PY" /usr/bin/deluge-web -c "$ROOT/de" -p 18112 -i 127.0.0.1 -L warning -l "$ROOT/deweb.log" &
else
  echo "interop: no Python can import Deluge; Deluge daemon not started" >&2
fi

for i in $(seq 1 30); do
  tr=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19091/transmission/rpc || true)
  qb=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/ || true)
  de=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
       -d '{"method":"auth.login","params":["deluge"],"id":1}' http://127.0.0.1:18112/json || true)
  if [ "$tr" = 401 ] && [ "$qb" = 200 ] && { [ -z "$PY" ] || [ "$de" = 200 ]; }; then
    echo "interop: transmission=$tr qbittorrent=$qb deluge=${de:-absent} root=$ROOT"
    exit 0
  fi
  sleep 1
done
echo "interop: daemons did not become ready (tr=$tr qb=$qb de=$de)" >&2
exit 1
