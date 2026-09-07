#!/usr/bin/env python3
"""Validate the managed LLM dashboard queries and their bounded dimensions."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = ROOT / "infrastructure/observability/signoz/dashboards/llm.json"
REQUIRED_PANELS = {
    "LLM request rate",
    "LLM request latency p95",
    "LLM error ratio",
    "LLM token rate",
    "LLM tokens per request",
    "LLM estimated cost rate",
    "LLM cost availability",
}
ALLOWED_GROUPS = {"le", "provider", "model", "outcome", "direction", "currency", "status"}
FORBIDDEN_LABELS = {"task_id", "message_id", "workflow_id", "context_id", "prompt", "completion",
                    "response", "api_key", "authorization", "tenant_id", "caller"}


def main() -> None:
    document = json.loads(DASHBOARD.read_text(encoding="utf-8"))
    panels = document["spec"]["panels"].values()
    titles = {panel["spec"]["display"]["name"] for panel in panels}
    if titles != REQUIRED_PANELS:
        raise SystemExit(f"LLM dashboard panels differ: {sorted(titles ^ REQUIRED_PANELS)}")
    queries = [
        query["spec"]["query"]
        for panel in panels
        for wrapper in panel["spec"]["queries"]
        for query in wrapper["spec"]["plugin"]["spec"]["queries"]
    ]
    for query in queries:
        lowered = query.lower()
        leaked = sorted(label for label in FORBIDDEN_LABELS if re.search(rf"\\b{label}\\b", lowered))
        if leaked:
            raise SystemExit(f"Sensitive or unbounded LLM dashboard labels {leaked}: {query}")
        for clause in re.findall(r"by\\s*\\(([^)]*)\\)", query):
            labels = {value.strip() for value in clause.split(",")}
            if not labels <= ALLOWED_GROUPS:
                raise SystemExit(f"Unknown LLM dashboard dimensions {sorted(labels - ALLOWED_GROUPS)}: {query}")
    print(f"LLM SigNoz dashboard validated: {len(titles)} panels, {len(queries)} bounded queries.")


if __name__ == "__main__":
    main()
