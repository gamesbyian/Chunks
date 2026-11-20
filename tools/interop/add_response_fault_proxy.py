#!/usr/bin/env python3
"""Loopback-only HTTP relay for installed tests against disposable daemons."""

import argparse
import http.client
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


# Fixed test fixtures. No request can choose an upstream host or port.
PORTS = {"transmission": 19091, "qbittorrent": 18080, "deluge": 18112}
ADD = {
    "transmission": {"torrent-add"},
    "qbittorrent": {"/api/v2/torrents/add"},
    "deluge": {"core.add_torrent_file", "core.add_torrent_magnet"},
}
START = {
    "transmission": {"torrent-start", "torrent-start-now"},
    "qbittorrent": {"/api/v2/torrents/resume", "/api/v2/torrents/start"},
    "deluge": {"core.resume_torrent", "core.resume_torrents", "core.resume_all_torrents"},
}
CONTROL = "/__chunks_add_response_fault/"
HOP_HEADERS = {"connection", "keep-alive", "proxy-connection", "transfer-encoding",
               "upgrade", "trailer", "te", "content-length", "host"}
COUNTERS = ("adds", "acknowledgments_withheld", "starts", "upstream_errors")


class FaultState:
    def __init__(self):
        self.lock = threading.Lock()
        self.mode = None
        self.counts = dict.fromkeys(COUNTERS, 0)

    def arm(self, mode):
        with self.lock:
            self.counts = dict.fromkeys(COUNTERS, 0)
            self.mode = mode

    def begin(self, is_add, is_start):
        with self.lock:
            if self.mode is not None:
                self.counts["adds"] += int(is_add)
                self.counts["starts"] += int(is_start)
            return self.mode if is_add else None

    def record(self, key, delta=1):
        with self.lock:
            self.counts[key] += delta

    def report(self):
        with self.lock:
            return self.counts.copy()


def rpc_method(kind, path, body):
    if kind == "qbittorrent":
        return path
    if path != {"transmission": "/transmission/rpc", "deluge": "/json"}[kind]:
        return None
    data = json.loads(body) if body else {}
    return data.get("method") if isinstance(data, dict) else None


def accepted_add(kind, status, response):
    if status not in (200, 204):
        return False
    if kind == "qbittorrent":
        return response.strip() in (b"", b"Ok.")
    data = json.loads(response)
    if kind == "transmission":
        return data.get("result") == "success" and "torrent-added" in data.get("arguments", {})
    return data.get("error") is None and isinstance(data.get("result"), str)


class FaultProxy(BaseHTTPRequestHandler):
    def log_message(self, _format, *args):
        # Never log headers, metainfo, RPC bodies or URLs.
        pass

    def respond(self, status, body=b"", headers=()):
        self.send_response(status)
        for name, value in headers:
            if name.lower() not in HOP_HEADERS | {"server", "date"}:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def relay(self):
        path = urlsplit(self.path).path
        state = self.server.faults
        kind = self.server.kind
        if path.startswith(CONTROL):
            action = path.removeprefix(CONTROL)
            if self.command == "POST" and action in {"drop", "timeout"}:
                state.arm(action)
                return self.respond(204)
            if self.command == "GET" and action == "counts":
                return self.respond(200, json.dumps(state.report()).encode())
            return self.respond(404)

        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 8 * 1024 * 1024 or not self.path.startswith("/"):
            return self.respond(400)
        body = self.rfile.read(length) if length else b""
        method = rpc_method(kind, path, body) if self.command == "POST" else None
        mode = state.begin(method in ADD[kind], method in START[kind])
        request_headers = {name: value for name, value in self.headers.items()
                           if name.lower() not in HOP_HEADERS}

        upstream = http.client.HTTPConnection("127.0.0.1", PORTS[kind], timeout=10)
        try:
            upstream.request(self.command, self.path, body if length else None, request_headers)
            reply = upstream.getresponse()
            status, headers, response = reply.status, reply.getheaders(), reply.read()
            accepted = mode is not None and accepted_add(kind, status, response)
        except (OSError, http.client.HTTPException, ValueError):
            if mode is not None:
                state.record("upstream_errors")
            return self.respond(502)
        finally:
            upstream.close()

        # Transmission's CSRF challenge is issued before RPC dispatch.
        if mode is not None and kind == "transmission" and status == 409:
            state.record("adds", -1)
        if accepted:
            state.record("acknowledgments_withheld")
            if mode == "timeout":
                # Exceeds the app's real 15 s HTTP read timeout. The reply is
                # already received here, and other RPCs remain serviceable.
                time.sleep(18)
                return
            return self.respond(503)
        return self.respond(status, response, headers)

    def handle_relay(self):
        try:
            self.relay()
        except (OSError, ValueError):
            # The app may close a timed-out request. No request diagnostic
            # should print any part of the fixture's RPC traffic.
            self.close_connection = True

    do_GET = do_POST = do_HEAD = handle_relay


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=PORTS)
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.port), FaultProxy) as server:
        server.kind = args.kind
        server.faults = FaultState()
        server.serve_forever()


if __name__ == "__main__":
    main()
