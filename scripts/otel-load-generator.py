#!/usr/bin/env python3
"""Send bounded, content-free OTLP/HTTP JSON load to a Collector."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import time
import urllib.request
import uuid


RESOURCE = {
    "attributes": [
        {"key": "service.name", "value": {"stringValue": "otel-load-generator"}},
        {"key": "service.namespace", "value": {"stringValue": "ai-software-factory"}},
        {"key": "deployment.environment.name", "value": {"stringValue": "load-test"}},
    ]
}


def attribute(key: str, value: str) -> dict:
    return {"key": key, "value": {"stringValue": value}}


def payloads(points: int) -> list[tuple[str, bytes]]:
    now = time.time_ns()
    dimensions = [attribute("ai.operation", "load_test"), attribute("ai.outcome", "success")]
    metric_points = [
        {"timeUnixNano": str(now + index), "asDouble": 1.0, "attributes": dimensions}
        for index in range(points)
    ]
    spans = []
    logs = []
    for index in range(points):
        trace_id = uuid.uuid4().hex
        spans.append(
            {
                "traceId": trace_id,
                "spanId": uuid.uuid4().hex[:16],
                "name": "otel.load.probe",
                "kind": 1,
                "startTimeUnixNano": str(now + index * 1000),
                "endTimeUnixNano": str(now + index * 1000 + 500),
                "attributes": dimensions,
                "status": {"code": 1},
            }
        )
        logs.append(
            {
                "timeUnixNano": str(now + index),
                "severityNumber": 9,
                "severityText": "INFO",
                "body": {"stringValue": "bounded telemetry load probe"},
                "traceId": trace_id,
                "spanId": spans[-1]["spanId"],
                "attributes": dimensions,
            }
        )

    documents = {
        "metrics": {"resourceMetrics": [{"resource": RESOURCE, "scopeMetrics": [{"scope": {"name": "ai-factory-load"}, "metrics": [{"name": "ai_factory_otel_load_probe", "unit": "1", "gauge": {"dataPoints": metric_points}}]}]}]},
        "traces": {"resourceSpans": [{"resource": RESOURCE, "scopeSpans": [{"scope": {"name": "ai-factory-load"}, "spans": spans}]}]},
        "logs": {"resourceLogs": [{"resource": RESOURCE, "scopeLogs": [{"scope": {"name": "ai-factory-load"}, "logRecords": logs}]}]},
    }
    return [(signal, json.dumps(document, separators=(",", ":")).encode()) for signal, document in documents.items()]


def send(endpoint: str, signal: str, body: bytes, timeout: float) -> None:
    request = urllib.request.Request(
        f"{endpoint.rstrip('/')}/v1/{signal}", body, {"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status not in (200, 202):
            raise RuntimeError(f"OTLP {signal} returned HTTP {response.status}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://otel-collector:4318")
    parser.add_argument("--batches", type=int, default=50)
    parser.add_argument("--points", type=int, default=20)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()
    if not (1 <= args.batches <= 2000 and 1 <= args.points <= 200 and 1 <= args.workers <= 64):
        parser.error("batches, points or workers exceed the bounded test limits")

    started = time.monotonic()
    work = [item for _ in range(args.batches) for item in payloads(args.points)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(send, args.endpoint, signal, body, args.timeout) for signal, body in work]
        for future in concurrent.futures.as_completed(futures):
            future.result()
    elapsed = time.monotonic() - started
    records = args.batches * args.points * 3
    print(f"Accepted {records} records in {elapsed:.3f}s ({records / elapsed:.1f} records/s).")


if __name__ == "__main__":
    main()
