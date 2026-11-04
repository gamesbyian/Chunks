#!/usr/bin/env python3
"""Disposable-only fault switch for the two-server Combined journey.

GET /offline-a stops server A's own rTorrent daemon (pid file) and returns
200 only after its XML-RPC route no longer answers successfully. Loopback
only; it never touches anything except that disposable process.
"""
import http.server
import os
import signal
import sys
import time
import urllib.request

ROOT = sys.argv[1]


def a_answers():
    body = b'<?xml version="1.0"?><methodCall><methodName>system.client_version</methodName></methodCall>'
    try:
        req = urllib.request.Request("http://127.0.0.1:18080/RPC2", data=body,
                                     headers={"Content-Type": "text/xml"})
        with urllib.request.urlopen(req, timeout=3) as reply:
            return reply.status == 200 and b"<methodResponse>" in reply.read()
    except Exception:
        return False


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/offline-a":
            self.send_response(404)
            self.end_headers()
            return
        with open(os.path.join(ROOT, "a", "rtorrent.pid")) as handle:
            pid = int(handle.read().strip())
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        for _ in range(60):
            if not a_answers():
                break
            time.sleep(0.5)
        ok = not a_answers()
        print("CHUNKS_COMBINED_FAULT: server_a_offline=%s" % ok, flush=True)
        self.send_response(200 if ok else 500)
        self.end_headers()
        self.wfile.write(b"offline" if ok else b"still-answering")

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", 18083), Handler).serve_forever()
