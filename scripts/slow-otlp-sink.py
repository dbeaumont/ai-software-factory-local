#!/usr/bin/env python3
"""Deliberately slow, content-free OTLP test destination."""

import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    received = 0
    lock = threading.Lock()

    def do_POST(self) -> None:
        length = min(int(self.headers.get("Content-Length", "0")), 4 * 1024 * 1024)
        self.rfile.read(length)
        time.sleep(float(os.environ.get("OTLP_SINK_DELAY_SECONDS", "2")))
        with self.lock:
            type(self).received += 1
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def do_GET(self) -> None:
        with self.lock:
            body = str(type(self).received).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args: object) -> None:
        return


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
