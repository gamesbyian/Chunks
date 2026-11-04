#!/usr/bin/env python3
"""Disposable-only fault switch for the two-server Combined journey.

GET /rotate-auth-a changes A's disposable basic-auth password (the saved app
login is then rejected) and /restore-auth-a puts it back. /freeze-a SIGSTOPs server A's own rTorrent daemon (unresponsive, not
refused), /thaw-a resumes it and /offline-a terminates it. Each returns 200
only after A's XML-RPC route is observed in the requested state. Loopback
only; it never touches anything except that disposable process.
"""
import base64
import http.server
import os
import subprocess
import signal
import sys
import time
import urllib.request

ROOT = sys.argv[1]
USER = "chunks"
HTPASSWD = "/tmp/chunks-combined-a.htpasswd"
PASSWORDS = {"current": "chunks-one"}


def write_htpasswd(password):
    hashed = subprocess.run(["openssl", "passwd", "-apr1", password], check=True,
                            capture_output=True, text=True).stdout.strip()
    # nginx workers (not this user) read it: keep it in world-readable /tmp.
    with open(HTPASSWD, "w") as handle:
        handle.write("%s:%s\n" % (USER, hashed))
    os.chmod(HTPASSWD, 0o644)
    subprocess.run(["sudo", "-n", "nginx", "-s", "reload", "-c", os.path.join(ROOT, "nginx.conf")], check=True)
    time.sleep(1)


def a_answers(password=None):
    body = b'<?xml version="1.0"?><methodCall><methodName>system.client_version</methodName></methodCall>'
    token = base64.b64encode(("%s:%s" % (USER, password or PASSWORDS["current"])).encode()).decode()
    try:
        req = urllib.request.Request("http://127.0.0.1:18080/RPC2", data=body,
                                     headers={"Content-Type": "text/xml", "Authorization": "Basic " + token})
        with urllib.request.urlopen(req, timeout=3) as reply:
            return reply.status == 200 and b"<methodResponse>" in reply.read()
    except Exception:
        return False


class Handler(http.server.BaseHTTPRequestHandler):
    def reply(self, ok, label):
        print("CHUNKS_COMBINED_FAULT: %s=%s" % (label, ok), flush=True)
        self.send_response(200 if ok else 500)
        self.end_headers()
        self.wfile.write(b"ok" if ok else b"failed")

    def do_GET(self):
        if self.path == "/rotate-auth-a":
            # The saved app login ("chunks-one") must now be rejected.
            write_htpasswd("chunks-two")
            PASSWORDS["current"] = "chunks-two"
            self.reply(a_answers("chunks-two") and not a_answers("chunks-one"), "server_a_auth_rotated")
            return
        if self.path == "/restore-auth-a":
            write_htpasswd("chunks-one")
            PASSWORDS["current"] = "chunks-one"
            self.reply(a_answers("chunks-one"), "server_a_auth_restored")
            return
        if self.path not in ("/offline-a", "/freeze-a", "/thaw-a"):
            self.send_response(404)
            self.end_headers()
            return
        with open(os.path.join(ROOT, "a", "rtorrent.pid")) as handle:
            pid = int(handle.read().strip())
        if self.path == "/freeze-a":
            # Unresponsive daemon: the gateway accepts but nothing answers.
            os.kill(pid, signal.SIGSTOP)
            self.reply(not a_answers(), "server_a_frozen")
            return
        if self.path == "/thaw-a":
            os.kill(pid, signal.SIGCONT)
            for _ in range(60):
                if a_answers():
                    break
                time.sleep(0.5)
            self.reply(a_answers(), "server_a_thawed")
            return
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
