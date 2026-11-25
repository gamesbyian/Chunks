# source tools/interop/local_env.sh <root used by start_local_clients.sh>
_root="$(realpath -m "${1:-build/interop}")"
export CHUNKS_INTEROP_TRANSMISSION_URL=http://127.0.0.1:19091/transmission/rpc
export CHUNKS_INTEROP_TRANSMISSION_USER=chunks CHUNKS_INTEROP_TRANSMISSION_PASS=chunkspass
export CHUNKS_INTEROP_TRANSMISSION_PAYLOAD_DIR="$_root/dl/tr"
export CHUNKS_INTEROP_QBITTORRENT_URL=http://127.0.0.1:18080/
export CHUNKS_INTEROP_QBITTORRENT_USER=chunks CHUNKS_INTEROP_QBITTORRENT_PASS=chunkspass
export CHUNKS_INTEROP_QBITTORRENT_PAYLOAD_DIR="$_root/dl/qb"
if [ -f "$_root/de/hostlist.conf" ]; then
  export CHUNKS_INTEROP_DELUGE_URL=http://127.0.0.1:18112/json
  export CHUNKS_INTEROP_DELUGE_PASS=deluge
  export CHUNKS_INTEROP_DELUGE_PAYLOAD_DIR="$_root/dl/de"
  if [ -f "$_root/de2web/hostlist.conf" ]; then
    export CHUNKS_INTEROP_DELUGE2_URL=http://127.0.0.1:18113/json
    export CHUNKS_INTEROP_DELUGE2_PASS=deluge
    export CHUNKS_INTEROP_DELUGE2_HOST_A=c0ffee00c0ffee00c0ffee00c0ffee00
    export CHUNKS_INTEROP_DELUGE2_HOST_B=decaf000decaf000decaf000decaf000
  fi
fi
if [ -f "$_root/rt/rtorrent.rc" ]; then
  export CHUNKS_INTEROP_RTORRENT_URL=http://127.0.0.1:18090/RPC2
  export CHUNKS_INTEROP_RTORRENT_USER=chunks CHUNKS_INTEROP_RTORRENT_PASS=chunkspass
  export CHUNKS_INTEROP_RTORRENT_PAYLOAD_DIR="$_root/dl/rt"
fi
export CHUNKS_INTEROP_TRACKER=http://127.0.0.1:16969/announce
