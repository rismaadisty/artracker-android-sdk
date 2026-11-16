#!/usr/bin/env python3
"""A stand-in for the ingest backend, for the on-device checks that need no real backend (scripts/e2e-*.sh).

POST /api/v1/ingest   gzip (or plain) JSON envelope -> keeps its items, answers 202 {"status":"accepted","queued":n}
GET  /events          every item received so far, as a JSON list
Everything else (the Session Replay API included) answers 404, which the SDK treats as "no replay endpoint".

Usage: mock-ingest.py PORT   (binds 127.0.0.1; reach it from a device with `adb reverse tcp:PORT tcp:PORT`)
"""
import gzip
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ITEMS = []
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # quiet
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if not self.path.startswith("/api/v1/ingest"):
            return self._send(404, {"error": "not_found"})
        try:
            raw = gzip.decompress(body) if self.headers.get("Content-Encoding") == "gzip" else body
            items = json.loads(raw).get("items", [])
        except Exception:
            return self._send(400, {"error": "bad_request"})
        with LOCK:
            ITEMS.extend(items)
        self._send(202, {"status": "accepted", "queued": len(items)})

    def do_GET(self):
        if self.path.startswith("/events"):
            with LOCK:
                return self._send(200, list(ITEMS))
        self._send(404, {"error": "not_found"})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
