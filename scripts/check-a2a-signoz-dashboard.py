#!/usr/bin/env python3
"""Validate the managed A2A dashboard surface and its bounded PromQL dimensions."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = ROOT / "infrastructure/observability/signoz/dashboards/a2a.json"
REQUIRED_PANELS = {
    "Agent fleet",
    "Active tasks and backlog",
    "Client latency p95 by role and skill",
    "Server task latency p95 by role and skill",
    "Task state transitions",
    "Protocol and admission errors",
    "Temporal to A2A divergence",
    "Retries, timeouts and reconciliations",
    "Polling and notifications",
    "Admission saturation",
    "SLO dispatch availability",
    "SLO pickup delay p95",
    "SLO terminal delay p95",
    "SLO duplicate execution invariant",
    "SLO cancellation propagation p95",
}
ALLOWED_GROUPS = {"le", "agent_role", "agent_skill", "rpc_operation", "a2a_version", "task_state", "result"}
FORBIDDEN_LABELS = {"task_id", "message_id", "workflow_id", "context_id", "delegation_id", "tenant_id", "caller"}


def main() -> None:
    document = json.loads(DASHBOARD.read_text(encoding="utf-8"))
    panels = document["spec"]["panels"].values()
    titles = {panel["spec"]["display"]["name"] for panel in panels}
    if titles != REQUIRED_PANELS:
        raise SystemExit(f"A2A dashboard panels differ: {sorted(titles ^ REQUIRED_PANELS)}")
    queries = [
        query["spec"]["query"]
        for panel in panels
        for wrapper in panel["spec"]["queries"]
        for query in wrapper["spec"]["plugin"]["spec"]["queries"]
    ]
    for query in queries:
        lowered = query.lower()
        leaked = sorted(label for label in FORBIDDEN_LABELS if re.search(rf"\b{label}\b", lowered))
        if leaked:
            raise SystemExit(f"Unbounded A2A dashboard labels {leaked}: {query}")
        for clause in re.findall(r"by\s*\(([^)]*)\)", query):
            labels = {value.strip() for value in clause.split(",")}
            if not labels <= ALLOWED_GROUPS:
                raise SystemExit(f"Unknown A2A dashboard dimensions {sorted(labels - ALLOWED_GROUPS)}: {query}")
    print(f"A2A SigNoz dashboard validated: {len(titles)} panels, {len(queries)} bounded queries.")


if __name__ == "__main__":
    main()
