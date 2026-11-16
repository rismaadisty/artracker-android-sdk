#!/usr/bin/env python3
"""A stand-in for the ingest backend, for the on-device checks that need no real backend (scripts/e2e-*.sh).

POST /api/v1/ingest   gzip (or plain) JSON envelope -> keeps its items, answers 202 {"status":"accepted","queued":n}
GET  /events          every item received so far, as a JSON list
GET  /requests        how many ingest POSTs arrived so far, as {"count": n} (counted even when STATUS is not 202)
Everything else (the Session Replay API included) answers 404, which the SDK treats as "no replay endpoint".

Usage: mock-ingest.py PORT [STATUS]   (STATUS: answer every ingest POST with it instead of 202, e.g. 401; binds 127.0.0.1; reach it from a device with `adb reverse tcp:PORT tcp:PORT`)
"""
import gzip
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ITEMS = []
REQUESTS = [0]
STATUS = [202]
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
        with LOCK:
            REQUESTS[0] += 1
        if STATUS[0] != 202:
            return self._send(STATUS[0], {"error": "forced_status"})
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
        if self.path.startswith("/requests"):
            with LOCK:
                return self._send(200, {"count": REQUESTS[0]})
        self._send(404, {"error": "not_found"})


if __name__ == "__main__":
    if len(sys.argv) > 2:
        STATUS[0] = int(sys.argv[2])
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
