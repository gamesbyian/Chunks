#!/usr/bin/env python3
"""Seed an isolated synthetic v1 torrent on the GitHub emulator host."""
from hashlib import sha1
from pathlib import Path
import signal
import sys
import time

import libtorrent as lt


def stop(_signal, _frame):
    raise SystemExit(0)


def main(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    piece = bytes(range(256)) * 1024
    pieces = 256
    payload = root / "doze-piece-fixture.bin"
    with payload.open("wb") as out:
        for _ in range(pieces):
            out.write(piece)
    metainfo = lt.bencode({b"info": {
        b"length": len(piece) * pieces,
        b"name": payload.name.encode(),
        b"piece length": len(piece),
        b"pieces": sha1(piece).digest() * pieces,
        b"private": 1,
    }})
    (root / "fixture.torrent").write_bytes(metainfo)
    session = lt.session({
        "listen_interfaces": "127.0.0.1:18791",
        "enable_dht": False, "enable_lsd": False, "enable_upnp": False,
        "enable_natpmp": False, "enable_outgoing_utp": False,
        "enable_incoming_utp": False, "upload_rate_limit": 256 * 1024,
    })
    # Treat the emulator's NAT as an ordinary rate-limited peer. An unrestricted
    # local peer could exhaust the entire payload before the idle window.
    addresses = lt.ip_filter()
    addresses.add_rule("0.0.0.0", "255.255.255.255", 1)
    session.set_peer_class_filter(addresses)
    handle = session.add_torrent({
        "ti": lt.torrent_info(str(root / "fixture.torrent")), "save_path": str(root)
    })
    handle.resume()
    signal.signal(signal.SIGTERM, stop)
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            status = handle.status()
            if status.is_seeding and session.listen_port() == 18791:
                print(f"CHUNKS_DOZE_PEER: seed_verified_bytes={payload.stat().st_size}", flush=True)
                (root / "ready").touch()
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Disposable peer did not verify its torrent and bind")
        while True:
            time.sleep(1)
    finally:
        print(f"CHUNKS_DOZE_PEER: payload_uploaded_bytes={handle.status().total_payload_upload}", flush=True)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
