#!/usr/bin/env python3
"""Validate the A2A SLO contract and its SigNoz metric coverage."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "resources/a2a/slo-policy-v1.json"
DASHBOARD = ROOT / "infrastructure/observability/signoz/dashboards/a2a.json"
REQUIRED = {
    "dispatchAvailability",
    "pickupDelay",
    "terminalDelay",
    "duplicateExecution",
    "cancellationPropagationDelay",
}


def main() -> None:
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    objectives = policy.get("objectives", {})
    if set(objectives) != REQUIRED:
        raise SystemExit(f"A2A SLO objectives differ: {sorted(set(objectives) ^ REQUIRED)}")
    if policy.get("evaluationWindow") != "P28D" or policy.get("minimumEligibleTasks", 0) < 100:
        raise SystemExit("A2A SLO evaluation window or minimum sample is invalid")
    if objectives["dispatchAvailability"].get("targetRatio") != 0.995:
        raise SystemExit("A2A dispatch availability target must remain 99.5% in policy v1")
    if objectives["duplicateExecution"].get("targetCount") != 0:
        raise SystemExit("A2A duplicate execution is a zero-tolerance invariant")
    dashboard = DASHBOARD.read_text(encoding="utf-8")
    missing = sorted({value["metric"] for value in objectives.values()} - {
        metric for metric in {value["metric"] for value in objectives.values()} if metric in dashboard
    })
    if missing:
        raise SystemExit(f"A2A SLO metrics absent from SigNoz dashboard: {missing}")
    forbidden = {"task_id", "message_id", "workflow_id", "context_id", "delegation_id", "tenant_id", "caller"}
    if forbidden & set(policy.get("dimensions", [])):
        raise SystemExit("A2A SLO policy contains unbounded dimensions")
    print("A2A SLO policy validated: 5 supervised objectives, 28-day window, bounded dimensions.")


if __name__ == "__main__":
    main()
