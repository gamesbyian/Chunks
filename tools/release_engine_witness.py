#!/usr/bin/env python3
"""Black-box Local Add and independently hashed peer bytes on the installed R8 release APK."""
import argparse
import hashlib
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import urlencode

import libtorrent as lt

from interop.loopback_tracker import Handler

PACKAGE = "io.github.gamesbyian.chunks"
PEER_PORT = 18792
TRACKER_PORT = 18793
PAYLOAD_NAME = "chunks-release-peer-fixture.bin"
PLACE = "Saves to: Downloads/Chunks Downloads"
ANDROID_FILE = f"/sdcard/Download/Chunks Downloads/{PAYLOAD_NAME}"
UI_FILE = "/data/local/tmp/chunks-release-engine-window.xml"


def adb(*args, timeout=15):
    return subprocess.run(["adb", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=True, timeout=timeout).stdout


def shell(*args, timeout=15):
    return adb("shell", shlex.join(args), timeout=timeout)


def window():
    capture = f"rm -f {UI_FILE} && uiautomator dump {UI_FILE} >/dev/null && cat {UI_FILE}"
    try:
        return ET.fromstring(adb("shell", capture))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
        return None


def labels(node):
    return node.get("text", "").strip(), node.get("content-desc", "").strip()


def locate(root, text):
    return [n for n in root.iter("node") if n.get("package") == PACKAGE and text in labels(n)]


def until(seconds, condition, label):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = condition()
        if result:
            return result
        time.sleep(0.5)
    raise RuntimeError(label)


def start_checked(root):
    matches = locate(root, "Start when added")
    if len(matches) != 1:
        return False
    parents = {child: parent for parent in root.iter() for child in parent}
    node = matches[0]
    # Compose may place the Switch on the same accessible node or alongside its label.
    for scope in (node, parents.get(node)):
        if scope is None:
            continue
        switches = [n for n in scope.iter("node")
                    if n.get("package") == PACKAGE and n.get("checkable") == "true"]
        if len(switches) == 1:
            return switches[0].get("checked") == "true" and switches[0].get("enabled") == "true"
    return False


def tap_add(source):
    display = re.findall(rb"(\d+)x(\d+)", shell("wm", "size"))
    if not display:
        raise RuntimeError("display dimensions unavailable")
    width, height = map(int, display[-1])

    def source_window():
        root = window()
        return root if root is not None and locate(root, source) else None

    until(35, source_window, "exact reviewed magnet is missing")
    print("CHUNKS_RELEASE_ENGINE: exact_source_visible", flush=True)

    place_seen = False
    start_seen = False
    for _ in range(8):
        root = window()
        if root is not None:
            place_seen |= len(locate(root, PLACE)) == 1
            start_seen |= start_checked(root)
            buttons = [n for n in locate(root, "Add transfer") if n.get("enabled") == "true"]
            if place_seen and start_seen and len(buttons) == 1:
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", buttons[0].get("bounds", ""))
                if bounds is None:
                    raise RuntimeError("reviewed Add bounds are unavailable")
                x1, y1, x2, y2 = map(int, bounds.groups())
                if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
                    raise RuntimeError("reviewed Add bounds are outside the display")
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                print("CHUNKS_RELEASE_ENGINE: exact_place_and_start_reviewed add_dispatched_once", flush=True)
                return
        shell("input", "swipe", str(width // 2), str(height * 3 // 4),
              str(width // 2), str(height // 3), "350")
    raise RuntimeError(f"review unavailable: place_seen={place_seen} start_seen={start_seen}")


def seed(root):
    piece_size = 16 * 1024
    payload = b"".join(hashlib.sha256(f"chunks release fixture piece {i}".encode()).digest()
                       for i in range(2048))
    (root / PAYLOAD_NAME).write_bytes(payload)
    info = {b"length": len(payload), b"name": PAYLOAD_NAME.encode(), b"piece length": piece_size,
            b"pieces": b"".join(hashlib.sha1(payload[i:i + piece_size]).digest()
                                for i in range(0, len(payload), piece_size)), b"private": 1}
    tracker = f"http://127.0.0.1:{TRACKER_PORT}/announce"
    session = lt.session({"listen_interfaces": f"127.0.0.1:{PEER_PORT}", "enable_dht": False,
                          "enable_lsd": False, "enable_upnp": False, "enable_natpmp": False,
                          "enable_outgoing_utp": False, "enable_incoming_utp": False})
    metainfo = root / "fixture.torrent"
    metainfo.write_bytes(lt.bencode({b"info": info, b"announce": tracker}))
    handle = session.add_torrent({"ti": lt.torrent_info(str(metainfo)), "save_path": str(root)})
    handle.resume()
    until(30, lambda: session.listen_port() == PEER_PORT and handle.status().is_seeding,
          "the isolated peer did not verify its own pieces")
    info_hash = hashlib.sha1(lt.bencode(info)).hexdigest()
    magnet = "magnet:?xt=urn:btih:" + info_hash + "&" + urlencode(
        {"dn": PAYLOAD_NAME, "tr": tracker, "x.pe": f"127.0.0.1:{PEER_PORT}"})
    return session, handle, magnet, info_hash, payload


def witness(root):
    root.mkdir(parents=True, exist_ok=False)
    tracker = ThreadingHTTPServer(("127.0.0.1", TRACKER_PORT), Handler)
    Thread(target=tracker.serve_forever, daemon=True).start()
    forwarded = []
    try:
        peer_session, peer, magnet, info_hash, payload = seed(root)
        for port in (PEER_PORT, TRACKER_PORT):
            adb("reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            forwarded.append(port)
        shell("test", "!", "-e", ANDROID_FILE)
        shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", magnet, "-n", f"{PACKAGE}/.MainActivity")
        tap_add(magnet)

        def downloaded():
            try:
                content = adb("exec-out", "cat " + shlex.quote(ANDROID_FILE))
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                return False
            return content == payload and peer.status().total_payload_upload >= len(payload)

        until(120, downloaded, "verified fixture bytes and independent peer upload were not observed")
        print(f"CHUNKS_RELEASE_ENGINE: infohash={info_hash} peer_port={peer_session.listen_port()} "
              f"bytes={len(payload)} sha256={hashlib.sha256(payload).hexdigest()} "
              f"peer_upload={peer.status().total_payload_upload}", flush=True)
    finally:
        tracker.shutdown()
        tracker.server_close()
        for port in forwarded:
            subprocess.run(["adb", "reverse", "--remove", f"tcp:{port}"], check=False, timeout=15)
        shell("rm", "-f", UI_FILE)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-root", type=Path, required=True)
    witness(parser.parse_args().fixture_root)
