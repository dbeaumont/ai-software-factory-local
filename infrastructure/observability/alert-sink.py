#!/usr/bin/env python3
"""Bounded local webhook sink for development alert routing."""

from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 65_536
SAFE_VALUE = re.compile(r"[^A-Za-z0-9_.:-]")


def safe(value: object) -> str:
    return SAFE_VALUE.sub("_", str(value))[:128]


class Handler(BaseHTTPRequestHandler):
    server_version = "ai-factory-alert-sink/1"

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok\n")

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/alerts":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400)
            return
        if length < 0 or length > MAX_BODY_BYTES:
            self.send_error(413)
            return
        try:
            payload = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_error(400)
            return
        alerts = payload.get("alerts", []) if isinstance(payload, dict) else []
        summaries = []
        for alert in alerts[:32]:
            labels = alert.get("labels", {}) if isinstance(alert, dict) else {}
            summaries.append(f"{safe(labels.get('alertname', 'unknown'))}:{safe(alert.get('status', 'unknown'))}")
        print(f"alert_count={len(alerts)} alerts={','.join(summaries)}", flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, format: str, *args: object) -> None:
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
