#!/usr/bin/env python3
import argparse
import concurrent.futures
import json
import math
import time
import urllib.request


def request_body(message_id: str, rpc_id: str) -> bytes:
    digest = "a" * 64
    envelope = {
        "jsonrpc": "2.0",
        "id": rpc_id,
        "method": "message/send",
        "params": {
            "configuration": {"blocking": False},
            "message": {
                "role": "ROLE_USER",
                "messageId": message_id,
                "parts": [{"kind": "data", "data": {
                    "schema_version": "1", "target_role": "developer",
                    "skill_id": "developer.code-task-v1", "input_references": [{"digest": digest}]
                }}],
                "metadata": {
                    "https://ai-factory.local/extensions/execution-context/v1": {
                        "schemaVersion": "1", "taskId": "perf-task", "attemptId": "attempt-1",
                        "workflowId": "perf-workflow", "workflowRunId": "run-1",
                        "repositoryId": "repository-1",
                        "sourceCommit": "0123456789abcdef0123456789abcdef01234567",
                        "delegationId": "delegation-performance", "agentRole": "developer",
                        "inputDigests": [digest]
                    },
                    "https://ai-factory.local/extensions/w3c-trace-context/v1": {
                        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                    }
                }
            }
        }
    }
    return json.dumps(envelope, separators=(",", ":")).encode()


def invoke(endpoint: str, message_id: str, rpc_id: str):
    body = request_body(message_id, rpc_id)
    request = urllib.request.Request(endpoint, data=body, method="POST", headers={
        "A2A-Version": "1.0", "Content-Type": "application/json", "Accept": "application/json"
    })
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    return (time.perf_counter() - started) * 1000, result, len(body)


def percentile(values, quantile):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * quantile) - 1)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("endpoint")
    parser.add_argument("--requests", type=int, default=300)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--saturation-requests", type=int, default=64)
    parser.add_argument("--max-active", type=int, default=32)
    parser.add_argument("--max-p95-ms", type=float, default=1000)
    parser.add_argument("--min-throughput", type=float, default=20)
    args = parser.parse_args()

    for sequence in range(10):
        elapsed, response, _ = invoke(args.endpoint, "perf-replay", f"warmup-{sequence}")
        assert "result" in response, (elapsed, response)

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(invoke, args.endpoint, "perf-replay", f"load-{sequence}")
                   for sequence in range(args.requests)]
        benchmark = [future.result() for future in futures]
    wall_seconds = time.perf_counter() - started
    assert all("result" in response for _, response, _ in benchmark)
    latencies = [elapsed for elapsed, _, _ in benchmark]
    throughput = args.requests / wall_seconds

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(invoke, args.endpoint, f"saturation-{sequence}", f"sat-{sequence}")
                   for sequence in range(args.saturation_requests)]
        saturation = [future.result() for future in futures]
    admitted = sum("result" in response for _, response, _ in saturation)
    rejected = [response for _, response, _ in saturation if "error" in response]
    expected_admitted = args.max_active - 1
    assert admitted == expected_admitted, (admitted, expected_admitted)
    assert len(rejected) == args.saturation_requests - expected_admitted
    assert all("QUOTA_EXCEEDED" in json.dumps(response) for response in rejected)

    result = {
        "schema_version": "1",
        "benchmark": {
            "requests": args.requests,
            "concurrency": args.concurrency,
            "payload_bytes": benchmark[0][2],
            "latency_ms": {
                "p50": round(percentile(latencies, 0.50), 3),
                "p95": round(percentile(latencies, 0.95), 3),
                "p99": round(percentile(latencies, 0.99), 3)
            },
            "throughput_requests_per_second": round(throughput, 3)
        },
        "saturation": {
            "requests": args.saturation_requests,
            "configured_max_active": args.max_active,
            "admitted": admitted,
            "quota_rejected": len(rejected)
        },
        "provider_cost": {"status": "NOT_APPLICABLE_PROTOCOL_ONLY", "value": None}
    }
    assert result["benchmark"]["latency_ms"]["p95"] <= args.max_p95_ms, result
    assert throughput >= args.min_throughput, result
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
