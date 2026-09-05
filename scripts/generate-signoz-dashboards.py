#!/usr/bin/env python3
"""Generate deterministic SigNoz v6/Perses dashboards for the local factory."""

from __future__ import annotations

import json
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "infrastructure/observability/signoz/dashboards"
NAMESPACE = uuid.UUID("6e2c58e1-8974-4d4b-83c5-3616b20aa0c6")

SEARCH_VARIABLES = (
    ("task_id", "Task identifier", "Paste an ai.task.id value to pivot to traces, logs and the task API."),
    ("role", "Agent role", "Bounded ai.agent.role or role value."),
    ("operation", "Operation", "Bounded ai.operation value."),
    ("outcome", "Result", "Bounded ai.outcome or outcome value."),
    ("model", "LLM model", "Bounded gen_ai.request.model value."),
    ("mcp_server", "MCP service", "Bounded server or mcp.server.name value."),
)

OPERATIONAL_LINKS = (
    {"name": "Search traces", "url": "/trace", "renderVariables": True},
    {"name": "Search logs", "url": "/logs-explorer", "renderVariables": True},
    {"name": "Temporal UI", "url": "http://localhost:8233", "renderVariables": True},
    {
        "name": "Task API",
        "url": "http://localhost:8088/api/tasks/$task_id",
        "renderVariables": True,
    },
    {
        "name": "Observability runbooks",
        "url": "https://github.com/dbeaumont/ai-software-factory-local/tree/main/docs/operations/runbooks",
        "renderVariables": True,
    },
)

DASHBOARDS = {
    "orchestrator": {
        "name": "AI Factory Global",
        "description": "Vue globale OpenTelemetry des requêtes, tâches, agents, files et MCP.",
        "panels": [
            ("HTTP requests", ['rate({__name__="http.server.requests.count"}[5m])']),
            ("Factory tasks", ["sum(ai_factory_tasks_submitted)", "sum(ai_factory_tasks_completed)", "sum(ai_factory_tasks_failed)"]),
            ("Agent success ratio", ['sum(rate({__name__="ai_agent_duration.count",outcome="success"}[5m])) / clamp_min(sum(rate({__name__="ai_agent_duration.count"}[5m])), 1e-9)']),
            ("Task queue saturation", ["max by (perimeter) (ai_task_queue_saturation_ratio)"]),
            ("MCP errors", ["sum by (server) (rate(mcp_client_errors[5m]))"]),
        ],
    },
    "supervisor": {
        "name": "AI Factory Supervisor",
        "description": "Décisions du superviseur, replans et bornes de délégation.",
        "panels": [
            ("Supervisor executions", ['sum by (outcome) (rate({__name__="ai_agent_duration.count",role="supervisor"}[5m]))']),
            ("Replans, contradictions and escalations", ["sum by (event) (rate(ai_workflow_events{event=~\"replan|contradiction|escalation\"}[5m]))"]),
            ("Delegation fan-out", ['max by (role) ({__name__="ai_delegation_fan_out.max"})']),
            ("Delegation depth", ['max by (role) ({__name__="ai_delegation_depth.max"})']),
        ],
    },
    "agents": {
        "name": "AI Factory Agents",
        "description": "Débit, coût, durée et résultats des agents.",
        "panels": [
            ("Token rate by role and direction", ["sum by (role, direction) (rate(ai_agent_tokens[5m]))"]),
            ("Cost rate by role", ["sum by (role) (rate(ai_agent_cost_micros[5m])) / 1000000"]),
            ("Agent latency p95", ['histogram_quantile(0.95, sum by (le, role) (rate({__name__="ai_agent_duration.bucket"}[5m])))']),
            ("Agent outcomes", ['sum by (role, outcome) (rate({__name__="ai_agent_duration.count"}[5m]))']),
        ],
    },
    "mcp": {
        "name": "AI Factory MCP",
        "description": "Appels, latence, retries et concurrence des serveurs MCP.",
        "panels": [
            ("MCP call rate", ["sum by (server, outcome) (rate(mcp_client_calls[5m]))"]),
            ("MCP latency p95", ['histogram_quantile(0.95, sum by (le, server) (rate({__name__="mcp_client_duration.bucket"}[5m])))']),
            ("MCP retries and errors", ["sum by (server) (rate(mcp_client_retries[5m]))", "sum by (server) (rate(mcp_client_errors[5m]))"]),
            ("MCP in-flight calls", ["sum by (server) (mcp_client_inflight)"]),
        ],
    },
    "sandbox": {
        "name": "AI Factory Sandbox",
        "description": "Résultats, concurrence, files et latence des exécutions sandbox.",
        "panels": [
            ("Sandbox job outcomes", ["rate(ai_factory_sandbox_jobs_completed[5m])", "rate(ai_factory_sandbox_jobs_failed[5m])", "sum by (reason) (rate(ai_factory_sandbox_jobs_rejected[5m]))"]),
            ("Sandbox running and queued", ["ai_factory_sandbox_jobs_running", "ai_factory_sandbox_jobs_queued"]),
            ("Sandbox queue latency", ['histogram_quantile(0.95, sum by (le) (rate({__name__="ai_factory_sandbox_job_queue_duration.bucket"}[5m])))']),
            ("Temporal sandbox task queue saturation", ["ai_task_queue_saturation_ratio{perimeter=\"sandbox\"}"]),
        ],
    },
    "temporal": {
        "name": "AI Factory Temporal",
        "description": "Santé, débit, latence et persistance Temporal collectés par le receiver du Collector.",
        "panels": [
            ("Temporal scrape readiness", ["up{job=\"temporal\"}"]),
            ("Frontend request and error rates", ["sum(rate(service_requests{service_name=\"frontend\"}[5m]))", "sum(rate(service_errors{service_name=\"frontend\"}[5m]))"]),
            ("Workflow task schedule-to-start p95", ['histogram_quantile(0.95, sum by (le) (rate({__name__="workflow_task_schedule_to_start_latency.bucket"}[5m])))']),
            ("Persistence errors", ["sum(rate(persistence_error_with_type[5m]))"]),
        ],
    },
    "collector": {
        "name": "AI Factory OpenTelemetry Collector",
        "description": "Santé, débit, refus et files du Collector OpenTelemetry local.",
        "panels": [
            ("Accepted telemetry", ["sum by (receiver) (rate(otelcol_receiver_accepted_spans[5m]))", "sum by (receiver) (rate(otelcol_receiver_accepted_metric_points[5m]))", "sum by (receiver) (rate(otelcol_receiver_accepted_log_records[5m]))"]),
            ("Refused telemetry", ["sum(rate(otelcol_receiver_refused_spans[5m]))", "sum(rate(otelcol_receiver_refused_metric_points[5m]))", "sum(rate(otelcol_receiver_refused_log_records[5m]))"]),
            ("Export failures", ["sum(rate(otelcol_exporter_send_failed_spans[5m]))", "sum(rate(otelcol_exporter_send_failed_metric_points[5m]))", "sum(rate(otelcol_exporter_send_failed_log_records[5m]))"]),
            ("Exporter queue utilization", ["max by (exporter) (otelcol_exporter_queue_size / clamp_min(otelcol_exporter_queue_capacity, 1))"]),
            ("Collector memory", ["otelcol_process_memory_rss"]),
        ],
    },
}


def query(name: str, expression: str) -> dict:
    return {
        "type": "promql",
        "spec": {"name": name, "query": expression, "disabled": False, "step": 0, "stats": False, "legend": ""},
    }


def panel(title: str, expressions: list[str]) -> dict:
    return {
        "kind": "Panel",
        "spec": {
            "display": {"name": title, "description": ""},
            "plugin": {
                "kind": "signoz/TimeSeriesPanel",
                "spec": {
                    "visualization": {"timePreference": "global_time", "fillSpans": False},
                    "formatting": {"unit": "none", "decimalPrecision": "2"},
                    "chartAppearance": {
                        "lineInterpolation": "spline",
                        "showPoints": False,
                        "lineStyle": "solid",
                        "fillMode": "none",
                        "spanGaps": {"fillOnlyBelow": False, "fillLessThan": ""},
                    },
                    "axes": {"softMin": None, "softMax": None, "isLogScale": False},
                    "legend": {"position": "bottom", "mode": "list", "customColors": None},
                    "thresholds": None,
                },
            },
            "queries": [
                {
                    "kind": "time_series",
                    "spec": {
                        "plugin": {
                            "kind": "signoz/CompositeQuery",
                            "spec": {"queries": [query(chr(65 + index), expression) for index, expression in enumerate(expressions)]},
                        }
                    },
                }
            ],
            "links": list(OPERATIONAL_LINKS),
        },
    }


def text_variable(name: str, label: str, description: str) -> dict:
    return {
        "kind": "TextVariable",
        "spec": {
            "name": name,
            "display": {"name": label, "description": description},
            "value": "",
            "constant": False,
        },
    }


def dashboard(slug: str, definition: dict) -> dict:
    panels = {}
    items = []
    for index, (title, expressions) in enumerate(definition["panels"]):
        panel_id = str(uuid.uuid5(NAMESPACE, f"{slug}:{index}:{title}"))
        panels[panel_id] = panel(title, expressions)
        items.append(
            {
                "x": 0 if index % 2 == 0 else 6,
                "y": (index // 2) * 6,
                "width": 6,
                "height": 6,
                "content": {"$ref": f"#/spec/panels/{panel_id}"},
            }
        )
    return {
        "schemaVersion": "v6",
        "image": "/assets/Icons/eight-ball",
        "generateName": True,
        "tags": [{"key": "project", "value": "ai-software-factory"}, {"key": "domain", "value": slug}],
        "spec": {
            "display": {"name": definition["name"], "description": definition["description"]},
            "variables": [text_variable(*definition) for definition in SEARCH_VARIABLES],
            "panels": panels,
            "layouts": [{"kind": "Grid", "spec": {"items": items}}],
            "duration": "6h",
            "refreshInterval": "30s",
            "links": list(OPERATIONAL_LINKS),
        },
    }


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for slug, definition in DASHBOARDS.items():
        path = OUTPUT / f"{slug}.json"
        path.write_text(json.dumps(dashboard(slug, definition), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
