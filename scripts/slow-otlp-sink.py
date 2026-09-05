#!/usr/bin/env python3
"""Deliberately slow, content-free OTLP test destination."""

import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = min(int(self.headers.get("Content-Length", "0")), 4 * 1024 * 1024)
        self.rfile.read(length)
        time.sleep(float(os.environ.get("OTLP_SINK_DELAY_SECONDS", "2")))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *_args: object) -> None:
        return


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
