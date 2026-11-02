#!/usr/bin/env python3
"""Real libtorrent metadata/payload peer for installed remote-Add acceptance.

Two independently published, redistributable v1 torrents: official Debian
netinst and Blender's CC BY 3.0 Sintel via WebTorrent. Only a bounded prefix
is fetched over HTTPS; libtorrent independently checks the original pieces.
The Android app submits magnets, never these metainfo bytes, to rTorrent.
The loopback controller only dials the configured disposable/development host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import signal
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import libtorrent as lt
from public_fixture_collector import debian_fixture

PREFIX_BUDGET = 32 * 1024 * 1024


def fetch(url, limit, offset=0):
    request = urllib.request.Request(url, headers={
        "User-Agent": "Chunks-real-magnet-acceptance/1.0",
        "Range": f"bytes={offset}-{offset + limit - 1}",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status not in (200, 206) or response.geturl().split(":", 1)[0] != "https":
            raise ValueError("Source did not return an HTTPS payload")
        if offset and response.status != 206:
            raise ValueError("Source ignored a nonzero range")
        data = response.read(limit)
    if len(data) != limit:
        raise ValueError("Source returned incomplete payload bytes")
    return data


def prepare(root):
    manifest, debian = debian_fixture(2 * 1024**3, architecture="arm64")
    sintel_url = "https://webtorrent.io/torrents/sintel.torrent"
    with urllib.request.urlopen(sintel_url, timeout=30) as response:
        sintel = response.read(2_097_153)
    if not sintel or len(sintel) > 2_097_152:
        raise ValueError("Sintel metainfo unavailable")
    sources = {
        "magnet": (debian, manifest["torrent_url"].replace("/bt-cd/", "/iso-cd/").removesuffix(".torrent"),
                   manifest["source_url"], manifest["license"]),
        "magnet2": (sintel, "https://webtorrent.io/torrents/", sintel_url,
                    "Blender Foundation Sintel, CC BY 3.0, https://durian.blender.org/sharing/"),
    }
    fixtures = {}
    for label, (raw, payload_source, source, license_text) in sources.items():
        ti = lt.torrent_info(raw)
        if ti.priv():
            raise ValueError("Only the fixed non-private public fixtures may be used")
        trackers = [t.url for t in ti.trackers() if urllib.parse.urlsplit(t.url).scheme in ("http", "https", "udp")]
        if not trackers:
            raise ValueError("Public source has no standard tracker")
        magnet = "magnet:?xt=urn:btih:" + str(ti.info_hashes().v1) + "".join("&tr=" + urllib.parse.quote(t, safe="") for t in trackers)
        (root / (label + ".magnet")).write_text(magnet)
        fs = ti.files()
        total = ti.total_size()
        if not ti.info_hashes().has_v1() or total <= 0 or total > 2 * 1024**3:
            raise ValueError("Invalid public v1 torrent")
        target = root / label
        target.mkdir(parents=True, exist_ok=False)
        # Whole original pieces only, so the independent native recheck can
        # demonstrate availability before any Android/rTorrent Add is tried.
        budget = min(total, PREFIX_BUDGET // ti.piece_length() * ti.piece_length())
        remaining = budget
        for index in range(fs.num_files()):
            relative = PurePosixPath(fs.file_path(index))
            if relative.is_absolute() or any(p in ("", ".", "..") for p in relative.parts):
                raise ValueError("Unsafe source payload path")
            path = target.joinpath(*relative.parts)
            path.parent.mkdir(parents=True, exist_ok=True)
            length = fs.file_size(index)
            with path.open("xb") as out:
                out.truncate(length)
                wanted = min(remaining, length)
                if wanted:
                    url = payload_source if label == "magnet" else payload_source + urllib.parse.quote(str(relative))
                    out.write(fetch(url, wanted))
                    remaining -= wanted
        (root / (label + ".torrent")).write_bytes(raw)
        fixtures[label] = {
            "hash": str(ti.info_hashes().v1), "name": ti.name(), "size": total,
            "prefix_bytes": budget, "source": source, "license": license_text,
            "metainfo_sha256": hashlib.sha256(raw).hexdigest(),
            "peer_connections": 0, "peer_disconnects": 0,
            "peer_error_alerts": 0, "max_active_peers": 0, "payload_uploaded_bytes": 0,
            "stages": [], "native_verified_bytes": 0,
        }
    return fixtures


class Peer:
    def __init__(self, root, target_host, fixtures):
        self.root, self.target_host, self.fixtures = root, target_host, fixtures
        self.active = {}
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.started = time.monotonic()

    def save(self):
        data = {"source_sha": os.getenv("GITHUB_SHA"), "run_id": os.getenv("GITHUB_RUN_ID"),
                "libtorrent_version": lt.version, "runtime_seconds": round(time.monotonic() - self.started, 2),
                "topology": "real libtorrent TCP peer to " + ("disposable rTorrent" if self.target_host == "127.0.0.1" else "approved development rTorrent"),
                "fixtures": self.fixtures}
        (self.root / "PEER-RECEIPT.json").write_text(json.dumps(data, indent=2) + "\n")

    def new_seed(self, label, stage, port=None):
        # The controller is not an arbitrary network probe or filesystem API.
        if label not in self.fixtures or stage not in ("preflight", "metadata", "payload", "resume"):
            raise ValueError("Invalid fixture or phase")
        if stage != "preflight" and (not isinstance(port, int) or not 1 <= port <= 65535):
            raise ValueError("Invalid observed peer port")
        with self.lock:
            item = self.fixtures[label]
            if stage in item["stages"]:
                raise ValueError("A peer phase must not be replayed")
            old = self.active.pop(label, None)
            if old:
                session, handle = old
                item["payload_uploaded_bytes"] += handle.status().total_payload_upload
                session.pause()
            session = lt.session({
                "listen_interfaces": "127.0.0.1:0", "enable_dht": False,
                "enable_lsd": False, "enable_upnp": False, "enable_natpmp": False,
                "enable_outgoing_utp": False, "enable_incoming_utp": False,
                "allow_multiple_connections_per_ip": True, "upload_rate_limit": 128 * 1024,
                "alert_mask": lt.alert.category_t.all_categories,
            })
            # Match LocalTorrentEngine's established local-peer rate-limit rig.
            classes = lt.ip_filter()
            classes.add_rule("0.0.0.0", "255.255.255.255", 1)
            session.set_peer_class_filter(classes)
            ti = lt.torrent_info(str(self.root / (label + ".torrent")))
            params = {"ti": ti, "save_path": str(self.root / label)}
            handle = session.add_torrent(params)
            # Never fetch missing bytes from public trackers or webseeds.
            handle.replace_trackers([])
            for webseed in handle.url_seeds():
                handle.remove_url_seed(webseed)
            handle.resume()
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                status = handle.status(lt.status_flags_t.query_pieces)
                verified = sum(ti.piece_size(i) for i, complete in enumerate(status.pieces) if complete)
                if status.error:
                    raise ValueError("Native source recheck failed")
                if verified >= item["prefix_bytes"]:
                    break
                time.sleep(.1)
            status = handle.status(lt.status_flags_t.query_pieces)
            verified = sum(ti.piece_size(i) for i, complete in enumerate(status.pieces) if complete)
            if verified < item["prefix_bytes"]:
                raise ValueError("Original source pieces are unavailable")
            item["native_verified_bytes"] = verified
            self.active[label] = session, handle
            item["stages"].append(stage)
            if stage != "preflight":
                handle.connect_peer((self.target_host, port))
            self.save()

    def monitor(self):
        while not self.stopping.wait(.1):
            with self.lock:
                for label, (session, handle) in self.active.items():
                    self.fixtures[label]["max_active_peers"] = max(
                        self.fixtures[label]["max_active_peers"], handle.status().num_peers
                    )
                    for alert in session.pop_alerts():
                        if isinstance(alert, lt.peer_connect_alert):
                            self.fixtures[label]["peer_connections"] += 1
                        elif isinstance(alert, lt.peer_disconnected_alert):
                            self.fixtures[label]["peer_disconnects"] += 1
                        elif isinstance(alert, lt.peer_error_alert):
                            self.fixtures[label]["peer_error_alerts"] += 1

    def close(self):
        self.stopping.set()
        with self.lock:
            for label, (session, handle) in self.active.items():
                self.fixtures[label]["payload_uploaded_bytes"] += handle.status().total_payload_upload
                session.pause()
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--peer-host", choices=("127.0.0.1", "5.79.112.219"), required=True)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=False)
    peer = None
    try:
        fixtures = prepare(args.root)
        peer = Peer(args.root, args.peer_host, fixtures)
        for label in fixtures:
            peer.new_seed(label, "preflight")
        threading.Thread(target=peer.monitor, daemon=True).start()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if self.path == "/ready":
                    body = json.dumps(peer.fixtures).encode()
                elif self.path in ("/magnet.torrent", "/magnet2.torrent", "/magnet.magnet", "/magnet2.magnet"):
                    body = (args.root / self.path[1:]).read_bytes()
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                parts = self.path.strip("/").split("/")
                if len(parts) != 2 or int(self.headers.get("Content-Length", "0")) > 128:
                    self.send_error(400)
                    return
                try:
                    body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                    peer.new_seed(parts[0], parts[1], body["port"])
                except Exception:
                    self.send_error(400, "Peer fixture phase failed")
                    return
                self.send_response(204)
                self.end_headers()

        server = ThreadingHTTPServer(("127.0.0.1", 18081), Handler)
        def stop(*_):
            raise KeyboardInterrupt()
        signal.signal(signal.SIGTERM, stop)
        print("CHUNKS_REAL_PEER: two original public payload prefixes independently piece-verified", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    except Exception as error:
        # Never copy source responses, paths, headers or credential-bearing data.
        (args.root / "PREPARATION-FAILURE.json").write_text(json.dumps({
            "source_sha": os.getenv("GITHUB_SHA"), "failure_category": type(error).__name__,
            "stage": "public-source-preparation", "installed_android": False,
        }) + "\n")
        raise SystemExit("Public real-peer preparation failed; no Android Add authorized")
    finally:
        if peer:
            peer.close()


if __name__ == "__main__":
    main()
