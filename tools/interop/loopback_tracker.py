"""Minimal BEP 3 HTTP tracker for disposable loopback interop only.

Every announcer is returned to the others as 127.0.0.1:<port> (compact
peers), so real clients on one machine can exchange pieces. No persistence,
no logging of peers or info hashes.
"""
import sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SWARMS = {}

def bencode(x):
    if isinstance(x, int):
        return b"i%de" % x
    if isinstance(x, bytes):
        return b"%d:%s" % (len(x), x)
    if isinstance(x, str):
        return bencode(x.encode())
    if isinstance(x, dict):
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in sorted(x.items())) + b"e"
    raise TypeError(type(x))

class Handler(BaseHTTPRequestHandler):
    # Default remains the same-host disposable-client fixture. Emulator
    # consumers may supply the host alias in their own subclass.
    peer_address = bytes([127, 0, 0, 1])

    def log_message(self, *args):
        pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/announce":
            self.send_response(404); self.end_headers(); return
        q = urllib.parse.parse_qs(parsed.query, keep_blank_values=True, encoding="latin-1")
        try:
            info_hash = q["info_hash"][0].encode("latin-1")
            port = int(q["port"][0])
        except (KeyError, ValueError, IndexError):
            body = bencode({"failure reason": "bad announce"})
        else:
            swarm = SWARMS.setdefault(info_hash, {})
            now = time.time()
            if q.get("event", [""])[0] == "stopped":
                swarm.pop(port, None)
            else:
                swarm[port] = now
            for p, seen in list(swarm.items()):
                if now - seen > 120:
                    swarm.pop(p, None)
            peers = b"".join(self.peer_address + p.to_bytes(2, "big") for p in swarm if p != port)
            body = bencode({"interval": 5, "min interval": 2, "peers": peers})
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 16969), Handler).serve_forever()
