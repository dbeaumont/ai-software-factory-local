#!/usr/bin/env python3
"""Generate the SigNoz business-parity and OpenTelemetry health alert rules."""

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
    ("AiFactoryCollectorExportFailures", "sum(increase(otelcol_exporter_send_failed_metric_points[5m])) + sum(increase(otelcol_exporter_send_failed_spans[5m])) > 0", 0, "1m", "critical", "observability", "Collector exports are failing", "The Collector cannot deliver metrics or traces to the local backend.", "/docs/operations/runbooks/COLLECTOR-INDISPONIBLE.md"),
    ("AiFactoryCollectorQueueSaturation", "max(otelcol_exporter_queue_size) > 1500", 0, "2m", "warning", "observability", "Collector export queue is saturating", "The bounded export queue exceeds 1500 pending items.", "/docs/operations/runbooks/COLLECTOR-INDISPONIBLE.md"),
    ("AiFactoryTelemetryIngestionAbsent", "absent_over_time(otelcol_receiver_accepted_metric_points[10m])", 0, "2m", "critical", "observability", "Collector metric ingestion is absent", "No accepted metric point has been observed for ten minutes.", "/docs/operations/runbooks/TELEMETRIE-ABSENTE.md"),
    ("AiFactoryCollectorRestart", "resets(otelcol_process_uptime[10m]) > 0", 0, "1m", "warning", "observability", "Collector restarted", "Collector uptime reset during the last ten minutes.", "/docs/operations/runbooks/COLLECTOR-INDISPONIBLE.md"),
    ("AiFactoryCollectorMemoryPressure", "max(otelcol_process_memory_rss) > 450000000", 0, "5m", "warning", "observability", "Collector memory is near its limit", "Collector RSS exceeds 450 MB under a 512 MB Compose limit.", "/docs/operations/runbooks/COUT-OBSERVABILITE.md"),
    ("AiFactoryCollectorReceiverRefused", "sum(increase(otelcol_receiver_refused_log_records[5m])) + sum(increase(otelcol_receiver_refused_metric_points[5m])) + sum(increase(otelcol_receiver_refused_spans[5m])) > 0", 0, "1m", "critical", "observability", "Collector refused telemetry", "The Collector rejected one or more log, metric or span records.", "/docs/operations/runbooks/TELEMETRIE-ABSENTE.md"),
    ("AiFactoryTemporalPollerAbsent", 'min(ai_temporal_task_queue_pollers{perimeter="workflow",task_type="workflow"}) < 1 or min(ai_temporal_task_queue_pollers{perimeter!="workflow",task_type="activity"}) < 1', 0, "2m", "critical", "temporal", "A required Temporal task queue has no poller", "The workflow queue or an activity perimeter has remained without a poller.", "/docs/operations/runbooks/TEMPORAL-INDISPONIBLE.md"),
    ("AiFactoryTemporalBacklogSustained", "max(ai_temporal_task_queue_backlog) > 20", 0, "10m", "warning", "temporal", "Temporal task queue backlog is sustained", "At least one bounded Temporal task queue has exceeded twenty pending tasks for ten minutes.", "/docs/operations/runbooks/SATURATION.md"),
    ("AiFactoryTemporalNonDeterministic", "increase(ai_temporal_workflow_nondeterministic[5m]) > 0", 0, "1m", "critical", "temporal", "Temporal workflow replay is non-deterministic", "A worker rejected persisted workflow history because the implementation is incompatible.", "/docs/operations/runbooks/TEMPORAL-INDISPONIBLE.md"),
    ("AiFactoryTemporalProjectionLag", "max(ai_temporal_projection_lag_seconds) > 60", 0, "5m", "warning", "temporal", "Temporal UI projection is stale", "The oldest current task projection has not been refreshed for more than sixty seconds.", "/docs/operations/runbooks/TEMPORAL-INDISPONIBLE.md"),
    ("AiFactoryTemporalActivityStuck", "increase(ai_temporal_timeouts[5m]) > 0", 0, "1m", "critical", "temporal", "Temporal activity or workflow timed out", "A Temporal execution exhausted a configured timeout and may require idempotent recovery.", "/docs/operations/runbooks/SATURATION.md"),
    ("AiFactoryTemporalContinueAsNewFailure", "increase(ai_temporal_continue_as_new_requested[10m]) > increase(temporal_workflow_continue_as_new[10m])", 0, "2m", "critical", "temporal", "Temporal continue-as-new did not complete", "A workflow requested history rollover without a matching successful continue-as-new.", "/docs/operations/runbooks/TEMPORAL-INDISPONIBLE.md"),
    ("AiFactoryA2aPollerAbsent", 'min(temporal_num_pollers{task_queue=~"a2a-agent-.*"}) < 1 or absent_over_time(temporal_num_pollers{task_queue=~"a2a-agent-.*"}[5m])', 0, "2m", "critical", "a2a", "An A2A agent Temporal queue has no poller", "No poller serves at least one required A2A agent task queue.", "/docs/operations/runbooks/A2A-AGENT-INDISPONIBLE.md"),
    ("AiFactoryA2aAgentNotReady", 'min({__name__="ai.factory.a2a.server.ready"}) < 1 or absent_over_time({__name__="ai.factory.a2a.server.ready"}[5m])', 0, "2m", "critical", "a2a", "An A2A agent is not ready", "At least one agent runtime reports a failed mandatory readiness dependency or no readiness signal.", "/docs/operations/runbooks/A2A-AGENT-INDISPONIBLE.md"),
    ("AiFactoryA2aCardInvalid", 'increase({__name__="ai.factory.a2a.client.card.validations",result="rejected"}[5m]) > 0', 0, "1m", "critical", "a2a", "An A2A Agent Card was rejected", "The orchestrator rejected an expired, forged, incompatible or unauthorized Agent Card.", "/docs/operations/runbooks/A2A-CARTE-INVALIDE.md"),
    ("AiFactoryA2aFailureRate", 'sum(rate({__name__="ai.factory.a2a.server.transitions",task_state=~"failed|rejected"}[5m])) / clamp_min(sum(rate({__name__="ai.factory.a2a.server.transitions"}[5m])), 1e-9) > 0.05', 0, "5m", "warning", "a2a", "A2A task failure rate exceeds five percent", "Terminal A2A failures or rejections exceed five percent of task transitions.", "/docs/operations/runbooks/A2A-AGENT-INDISPONIBLE.md"),
    ("AiFactoryA2aBacklog", 'max({__name__="ai.factory.a2a.server.backlog"}) > 20', 0, "10m", "warning", "a2a", "A2A backlog is sustained", "At least one agent runtime has more than twenty submitted tasks for ten minutes.", "/docs/operations/runbooks/A2A-SATURATION.md"),
    ("AiFactoryA2aTaskStuck", 'max({__name__="ai.factory.a2a.server.oldest.active.age"}) > 300', 0, "5m", "critical", "a2a", "An A2A task is stuck", "The oldest non-terminal A2A task has remained active for more than five minutes.", "/docs/operations/runbooks/A2A-TACHE-BLOQUEE.md"),
    ("AiFactoryA2aNotificationLate", 'max({__name__="ai.factory.a2a.client.notification.age.max"}) > 60000', 0, "2m", "warning", "a2a", "An A2A notification is late", "Push notification delivery age exceeds sixty seconds.", "/docs/operations/runbooks/A2A-CALLBACK-PERDU.md"),
    ("AiFactoryA2aIdempotencyCollision", 'increase({__name__="ai.factory.a2a.server.idempotency.collisions"}[5m]) > 0', 0, "1m", "critical", "a2a", "An A2A idempotency collision was rejected", "A reused message ID carried a different payload or continuation.", "/docs/operations/runbooks/A2A-DIVERGENCE-ETAT.md"),
    ("AiFactoryA2aStateDivergence", 'increase({__name__="ai.factory.a2a.client.divergences"}[5m]) > 0', 0, "1m", "critical", "a2a", "Temporal and A2A state diverged", "The orchestrator rejected a task association, continuation or remote response that changed durable correlation.", "/docs/operations/runbooks/A2A-DIVERGENCE-ETAT.md"),
]

OWNERS = {
    "a2a": "agent-platform",
    "temporal": "workflow-platform",
    "observability": "platform-observability",
}


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
        "labels": {"severity": severity, "component": component, "owner": OWNERS.get(component, "ai-factory"),
                   "managed_by": "ai-software-factory"},
        "annotations": {"summary": summary, "description": description, "runbook_url": runbook},
    }


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps([build_rule(rule) for rule in RULES], indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
