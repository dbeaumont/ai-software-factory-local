#!/usr/bin/env python3
"""Generate the SigNoz alert rules that replace the Prometheus rule group."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "infrastructure/observability/signoz/rules/ai-factory.json"

RULES = [
    ("AiFactoryAgentLoopDetected", 'increase(ai_agent_failures{reason=~"repeated_call|max_turns"}[5m]) > 0', 0, "1m", "warning", "agents", "Agent loop or turn limit detected", "An agent stopped after repeating an identical call or exhausting its turn limit.", "/docs/operations/runbooks/AGENT-DEFAILLANT.md"),
    ("AiFactoryAgentBudgetExhausted", 'increase(ai_agent_failures{stop_condition="BUDGET_EXHAUSTED"}[5m]) > 0', 0, "1m", "warning", "agents", "Agent execution budget exhausted", "A host-enforced token, cost, turn or fan-out budget stopped an agent.", "/docs/operations/runbooks/AGENT-DEFAILLANT.md"),
    ("AiFactoryAgentCostSpike", "sum(increase(ai_agent_cost_micros[15m])) > 5000000", 0, "5m", "warning", "agents", "Aggregate agent cost exceeds the operational threshold", "Agent usage exceeded 5,000,000 cost micros over fifteen minutes.", "/docs/operations/runbooks/AGENT-DEFAILLANT.md"),
    ("AiFactoryTaskQueueBacklog", "max(ai_factory_sandbox_jobs_queued) > 20 or max(ai_task_queue_saturation_ratio) > 0.9", 0, "10m", "warning", "queues", "Worker backlog or saturation is sustained", "More than twenty sandbox jobs are queued or a worker perimeter remains above 90 percent saturation.", "/docs/operations/runbooks/SATURATION.md"),
    ("AiFactorySandboxHeartbeatInvalid", "increase(ai_factory_sandbox_heartbeat_invalid[5m]) > 0", 0, "1m", "critical", "sandbox", "Sandbox execution heartbeat is absent, invalid or stale", "The orchestrator rejected a sandbox heartbeat and failed the execution closed.", "/docs/operations/runbooks/SANDBOX-BACKEND-INDISPONIBLE.md"),
    ("AiFactorySandboxExecutionFailures", "increase(ai_factory_sandbox_jobs_failed[10m]) > 5", 0, "5m", "warning", "sandbox", "Sandbox executions are repeatedly failing", "More than five sandbox executions failed, timed out or were cancelled in ten minutes.", "/docs/operations/runbooks/SANDBOX-BACKEND-INDISPONIBLE.md"),
    ("AiFactorySandboxMaintenanceFailure", "increase(ai_factory_sandbox_maintenance_failures[10m]) > 0", 0, "2m", "critical", "sandbox", "Sandbox cleanup or retention maintenance is failing", "The sandbox controller could not maintain persisted jobs or clean expired state.", "/docs/operations/runbooks/SANDBOX-BACKEND-INDISPONIBLE.md"),
    ("AiFactoryAgentContractError", 'increase(ai_agent_failures{stop_condition="CONTRACT_ERROR"}[5m]) > 0', 0, "1m", "warning", "contracts", "Agent output contract validation failed", "An agent emitted a malformed final turn or a result outside its declared output contract.", "/docs/operations/runbooks/AGENT-DEFAILLANT.md"),
    ("AiFactoryEvidenceAltered", "increase(ai_evidence_altered[5m]) > 0", 0, "1m", "critical", "evidence", "Evidence integrity verification failed", "Evidence MCP metadata, content or digest diverged from its workflow binding.", "/docs/operations/runbooks/MCP-COMPROMIS.md"),
]


def build_rule(definition: tuple) -> dict:
    name, expression, target, window, severity, component, summary, description, runbook = definition
    return {
        "alert": name,
        "alertType": "METRIC_BASED_ALERT",
        "description": description,
        "ruleType": "promql_rule",
        "version": "v5",
        "schemaVersion": "v2alpha1",
        "condition": {
            "compositeQuery": {
                "queryType": "promql",
                "panelType": "graph",
                "queries": [{"type": "promql", "spec": {"name": "A", "query": expression, "legend": ""}}],
            },
            "selectedQueryName": "A",
            "thresholds": {
                "kind": "basic",
                "spec": [{"name": "critical" if severity == "critical" else "warning", "op": "above", "matchType": "all_the_times", "target": target, "channels": ["ai-factory-local"]}],
            },
        },
        "evaluation": {"kind": "rolling", "spec": {"evalWindow": window, "frequency": "30s"}},
        "notificationSettings": {"groupBy": ["alertname", "component"], "renotify": {"enabled": True, "interval": "4h", "alertStates": ["firing"]}},
        "labels": {"severity": severity, "component": component, "managed_by": "ai-software-factory"},
        "annotations": {"summary": summary, "description": description, "runbook_url": runbook},
    }


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps([build_rule(rule) for rule in RULES], indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
