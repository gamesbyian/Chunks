#!/usr/bin/env python3
"""Loopback-only HTTP relay for installed tests against disposable daemons."""

import argparse
import http.client
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from xml.etree import ElementTree


# Fixed test fixtures. No request can choose an upstream host or port.
PORTS = {"transmission": 19091, "qbittorrent": 18080, "deluge": 18112, "rtorrent": 18090}
ADD = {
    "transmission": {"torrent-add"},
    "qbittorrent": {"/api/v2/torrents/add"},
    "deluge": {"core.add_torrent_file", "core.add_torrent_magnet"},
    "rtorrent": {"load.verbose", "load.raw_verbose", "load.start", "load.raw_start"},
}
START = {
    "transmission": {"torrent-start", "torrent-start-now"},
    "qbittorrent": {"/api/v2/torrents/resume", "/api/v2/torrents/start"},
    "deluge": {"core.resume_torrent", "core.resume_torrents", "core.resume_all_torrents"},
    "rtorrent": {"d.start", "d.open", "d.resume", "load.start", "load.raw_start"},
}
# Only Transmission's typed Stop / Remove are faulted (#2026 retained-receipt witnesses).
STOP = {"transmission": {"torrent-stop"}, "qbittorrent": set(), "deluge": set(), "rtorrent": set()}
REMOVE = {"transmission": {"torrent-remove"}, "qbittorrent": set(), "deluge": set(), "rtorrent": set()}
# Each held-reply mode faults exactly one typed command family.
HELD_COMMANDS = {"hold-stop": "stops", "hold-remove": "removes"}
CONTROL = "/__chunks_add_response_fault/"
HOP_HEADERS = {"connection", "keep-alive", "proxy-connection", "transfer-encoding",
               "upgrade", "trailer", "te", "content-length", "host"}
COUNTERS = ("adds", "acknowledgments_withheld", "starts", "upstream_errors", "view_mutations",
            "connect_reads_held", "stops", "removes")

# All writes to rTorrent view membership/filter/order which the installed app
# might send. The test's deliberate server-side hide uses the DIRECT 18090 RPC
# and never passes this relay (18190). Read-only inventory getters do not count.
VIEW_MUTATIONS = frozenset({
    "view.set_visible", "view.set_not_visible", "view.filter", "view.filter_all",
    "view.filter_download", "view.filter_on", "view.add", "view.insert",
    "view.erase", "view.sort", "view.sort_new", "view.sort_current",
    "view.persistent", "view.reopen", "d.views.push_back", "d.views.remove",
})


def rtorrent_view_mutation(path, body):
    """Classify app-issued XML-RPC view mutations, including system.multicall.

    This examines only method names and never logs or retains sensitive
    XML-RPC parameters, torrent bytes, credentials or source URLs.
    """
    if path != "/RPC2" or not body:
        return False
    root = ElementTree.fromstring(body)
    outer = root.findtext("methodName")
    methods = [outer]
    if outer in {"system.multicall", "system.multicall2"}:
        for member in root.findall(".//member"):
            if member.findtext("name") == "methodName":
                value = member.find("value")
                if value is not None:
                    methods.append((value.findtext("string") or value.text or "").strip())
    return any(method in VIEW_MUTATIONS for method in methods)


class FaultState:
    def __init__(self):
        self.lock = threading.Lock()
        self.mode = None
        self.release_signal = threading.Event()
        self.connect_read_release = None
        self.counts = dict.fromkeys(COUNTERS, 0)

    def arm(self, mode):
        with self.lock:
            self.counts = dict.fromkeys(COUNTERS, 0)
            self.mode = mode
            self.release_signal = threading.Event()

    def release_delayed_response(self):
        with self.lock:
            if self.mode != "delay":
                return False
            self.release_signal.set()
            return True

    def arm_connect_read(self):
        with self.lock:
            self.connect_read_release = threading.Event()
            self.counts["connect_reads_held"] = 0

    def hold_connect_read(self):
        with self.lock:
            if self.connect_read_release is None or self.counts["connect_reads_held"] != 0:
                return None
            self.counts["connect_reads_held"] = 1
            return self.connect_read_release

    def release_connect_read(self):
        with self.lock:
            if self.connect_read_release is None or self.counts["connect_reads_held"] != 1:
                return False
            self.connect_read_release.set()
            return True

    def begin(self, is_add, is_start, is_view_mutation=False, is_stop=False, is_remove=False):
        with self.lock:
            if self.mode is not None:
                self.counts["adds"] += int(is_add)
                self.counts["starts"] += int(is_start)
                self.counts["view_mutations"] += int(is_view_mutation)
                self.counts["stops"] += int(is_stop)
                self.counts["removes"] += int(is_remove)
            # A held-reply mode faults only its own typed command; every other mode only Add.
            faulted = (is_stop if self.mode == "hold-stop" else
                       is_remove if self.mode == "hold-remove" else is_add)
            return (self.mode, self.release_signal) if faulted else (None, None)

    def record(self, key, delta=1):
        with self.lock:
            self.counts[key] += delta

    def report(self):
        with self.lock:
            return self.counts.copy()


def rpc_method(kind, path, body):
    if kind == "qbittorrent":
        return path
    if kind == "rtorrent":
        if path == "/RPC2" and body:
            return ElementTree.fromstring(body).findtext("methodName")
        return None
    if path != {"transmission": "/transmission/rpc", "deluge": "/json"}[kind]:
        return None
    data = json.loads(body) if body else {}
    method = data.get("method") if isinstance(data, dict) else None
    return method if isinstance(method, str) else None


def accepted_stop(kind, status, response):
    """Transmission reports an executed torrent-stop / torrent-remove as result=success."""
    if kind != "transmission" or status != 200:
        return False
    return json.loads(response).get("result") == "success"


def accepted_add(kind, status, response):
    if status not in (200, 204):
        return False
    if kind == "qbittorrent":
        if response.strip() in (b"", b"Ok."):
            return True
        return json.loads(response).get("success_count", 0) == 1
    if kind == "rtorrent":
        result = ElementTree.fromstring(response)
        return result.tag == "methodResponse" and result.find("./params/param/value") is not None
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
            if self.command == "POST" and action in {"delay", "drop", "timeout", "hold", "hold-stop", "hold-remove"}:
                state.arm(action)
                return self.respond(204)
            if self.command == "POST" and action == "release":
                return self.respond(204 if state.release_delayed_response() else 409)
            if kind == "rtorrent" and self.command == "POST":
                if action == "hold-connect-read":
                    state.arm_connect_read()
                    return self.respond(204)
                if action == "release-connect-read":
                    return self.respond(204 if state.release_connect_read() else 409)
            if self.command == "GET" and action == "counts":
                return self.respond(200, json.dumps(state.report()).encode())
            return self.respond(404)

        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 8 * 1024 * 1024 or not self.path.startswith("/"):
            return self.respond(400)
        body = self.rfile.read(length) if length else b""
        method = rpc_method(kind, path, body) if self.command == "POST" else None
        # Attribute mutations sent by the *app* on this proxy. Changes made
        # directly by the disposable daemon or fixture bypass this counter.
        is_view_mutation = kind == "rtorrent" and rtorrent_view_mutation(path, body)
        mode, release = state.begin(method in ADD[kind], method in START[kind],
                                    is_view_mutation, method in STOP[kind], method in REMOVE[kind])
        request_headers = {name: value for name, value in self.headers.items()
                           if name.lower() not in HOP_HEADERS | {"accept-encoding"}}
        # The relay examines the upstream acknowledgment before deciding how
        # to fault it. HTTPConnection otherwise passes Android's gzip request
        # through without decoding the compressed body.
        request_headers["Accept-Encoding"] = "identity"

        upstream = http.client.HTTPConnection("127.0.0.1", PORTS[kind], timeout=10)
        try:
            upstream.request(self.command, self.path, body if length else None, request_headers)
            reply = upstream.getresponse()
            status, headers, response = reply.status, reply.getheaders(), reply.read()
            accepted = mode is not None and (
                accepted_stop(kind, status, response) if mode in HELD_COMMANDS
                else accepted_add(kind, status, response))
        except (OSError, http.client.HTTPException, ValueError, ElementTree.ParseError):
            if mode is not None:
                state.record("upstream_errors")
            return self.respond(502)
        finally:
            upstream.close()

        # Keep the cold-start read in flight while the installed app selects
        # Connections. The held Add and all of its counters remain untouched.
        if kind == "rtorrent" and method == "system.client_version":
            connect_release = state.hold_connect_read()
            if connect_release is not None and not connect_release.wait(12):
                return self.respond(503)

        # Transmission's CSRF challenge is issued before RPC dispatch.
        if mode is not None and kind == "transmission" and status == 409:
            state.record(HELD_COMMANDS.get(mode, "adds"), -1)
        if accepted:
            state.record("acknowledgments_withheld")
            if mode == "delay":
                if release.wait(10):
                    return self.respond(status, response, headers)
                state.record("upstream_errors")
                return self.respond(503)
            if mode in {"timeout", "hold"} or mode in HELD_COMMANDS:
                # Exceeds the app's real 15 s HTTP read timeout. The reply is
                # already received here, and other RPCs remain serviceable.
                time.sleep(18 if mode == "timeout" else 45)
                return
            return self.respond(503)
        return self.respond(status, response, headers)

    def handle_relay(self):
        try:
            self.relay()
        except (OSError, ValueError, ElementTree.ParseError):
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
