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
        "url": "https://github.com/dbeaumont/ai-factory-local/tree/main/docs/operations/runbooks",
        "renderVariables": True,
    },
)

TEMPORAL_LINKS = OPERATIONAL_LINKS + (
    {
        "name": "Temporal workflow history",
        "url": "http://localhost:8233/namespaces/$temporal_namespace/workflows/$workflow_id/$run_id/history",
        "renderVariables": True,
    },
)

TEMPORAL_VARIABLES = (
    ("temporal_namespace", "Temporal namespace", "Namespace containing the workflow execution."),
    ("workflow_id", "Temporal workflow ID", "Workflow execution identifier from traces or the task API."),
    ("run_id", "Temporal run ID", "Optional run identifier used for an exact history deep link."),
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
    "llm": {
        "name": "AI Factory LLM",
        "description": "Appels LLM, latence, erreurs, tokens et coût estimé, sans contenu de conversation.",
        "panels": [
            ("LLM request rate", ["sum by (provider, model, outcome) (rate(ai_factory_llm_requests_total[5m]))"]),
            ("LLM request latency p95", [
                "histogram_quantile(0.95, sum by (le, provider, model) (rate(ai_factory_llm_request_duration_seconds_bucket[5m])))",
            ]),
            ("LLM error ratio", [
                "sum by (provider, model) (rate(ai_factory_llm_requests_total{outcome=~\"error|timeout\"}[5m])) / clamp_min(sum by (provider, model) (rate(ai_factory_llm_requests_total[5m])), 1e-9)",
            ]),
            ("LLM token rate", ["sum by (provider, model, direction) (rate(ai_factory_llm_tokens_total[5m]))"]),
            ("LLM tokens per request", [
                "sum by (provider, model) (rate(ai_factory_llm_tokens_total[5m])) / clamp_min(sum by (provider, model) (rate(ai_factory_llm_requests_total[5m])), 1e-9)",
            ]),
            ("LLM estimated cost rate", [
                "sum by (provider, model, currency) (rate(ai_factory_llm_cost_micros_total[5m])) / 1000000",
            ]),
            ("LLM cost availability", [
                "sum by (provider, model, status) (rate(ai_factory_llm_cost_availability_total[5m]))",
            ]),
        ],
    },
    "a2a": {
        "name": "AI Factory A2A Fleet",
        "description": "Flotte A2A, latence, états, erreurs, divergences, retries, files et saturation.",
        "panels": [
            ("Agent fleet", ['count by (agent_role) ({__name__="ai.factory.a2a.server.active.tasks"})']),
            ("Active tasks and backlog", [
                'max by (agent_role) ({__name__="ai.factory.a2a.server.active.tasks"})',
                'max by (agent_role) ({__name__="ai.factory.a2a.server.backlog"})',
            ]),
            ("Client latency p95 by role and skill", [
                'histogram_quantile(0.95, sum by (le, agent_role, agent_skill) (rate({__name__="ai.factory.a2a.client.duration.bucket"}[5m])))',
            ]),
            ("Server task latency p95 by role and skill", [
                'histogram_quantile(0.95, sum by (le, agent_role, agent_skill) (rate({__name__="ai.factory.a2a.server.task.duration.bucket"}[5m])))',
            ]),
            ("Task state transitions", [
                'sum by (agent_role, agent_skill, task_state) (rate({__name__="ai.factory.a2a.server.transitions"}[5m]))',
            ]),
            ("Protocol and admission errors", [
                'sum by (agent_role) (rate({__name__="ai.factory.a2a.server.auth.refusals"}[5m]))',
                'sum by (agent_role, agent_skill) (rate({__name__="ai.factory.a2a.server.admissions.rejected"}[5m]))',
                'sum by (agent_role, result) (rate({__name__="ai.factory.a2a.client.card.validations"}[5m]))',
            ]),
            ("Temporal to A2A divergence", [
                'sum by (agent_role) (rate({__name__="ai.factory.a2a.client.divergences"}[5m]))',
            ]),
            ("Retries, timeouts and reconciliations", [
                'sum by (agent_role) (rate({__name__="ai.factory.a2a.client.retries"}[5m]))',
                'sum by (agent_role) (rate({__name__="ai.factory.a2a.client.timeouts"}[5m]))',
                'sum by (agent_role, result) (rate({__name__="ai.factory.a2a.client.reconciliations"}[5m]))',
            ]),
            ("Polling and notifications", [
                'sum by (agent_role, rpc_operation) (rate({__name__="ai.factory.a2a.server.polling"}[5m]))',
                'sum by (agent_role) (rate({__name__=~"ai.factory.a2a.server.notifications.*"}[5m]))',
            ]),
            ("Admission saturation", [
                'sum by (agent_role) (rate({__name__="ai.factory.a2a.server.admissions.rejected"}[5m]))',
                'max by (agent_role) ({__name__="ai.factory.a2a.server.backlog"})',
            ]),
            ("SLO dispatch availability", [
                'sum(rate({__name__="ai.factory.a2a.client.duration.count",rpc_operation="send",result="success"}[28d])) / clamp_min(sum(rate({__name__="ai.factory.a2a.client.duration.count",rpc_operation="send",result=~"success|error|timeout"}[28d])), 1e-9)',
            ]),
            ("SLO pickup delay p95", [
                'histogram_quantile(0.95, sum by (le, agent_role) (rate({__name__="ai.factory.a2a.server.pickup.duration.bucket"}[28d])))',
            ]),
            ("SLO terminal delay p95", [
                'histogram_quantile(0.95, sum by (le, agent_role) (rate({__name__="ai.factory.a2a.server.task.duration.bucket"}[28d])))',
            ]),
            ("SLO duplicate execution invariant", [
                'sum(increase({__name__="ai.factory.a2a.server.duplicate.executions"}[28d]))',
            ]),
            ("SLO cancellation propagation p95", [
                'histogram_quantile(0.95, sum by (le, agent_role) (rate({__name__="ai.factory.a2a.client.duration.bucket",rpc_operation="cancel",result="success"}[28d])))',
            ]),
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
        "description": "Santé client/worker, files, erreurs et attente humaine Temporal via OpenTelemetry.",
        "variables": TEMPORAL_VARIABLES,
        "links": TEMPORAL_LINKS,
        "panels": [
            ("Temporal scrape readiness", ["up{job=\"temporal\"}"]),
            ("Frontend request and error rates", ["sum(rate(service_requests{service_name=\"frontend\"}[5m]))", "sum(rate(service_errors{service_name=\"frontend\"}[5m]))"]),
            ("SDK client requests and failures", ["sum(rate(temporal_request[5m]))", "sum(rate(temporal_request_failure[5m]))"]),
            ("Worker pollers by task queue", ["sum by (task_queue, worker_type) (temporal_num_pollers)"]),
            ("Worker slot saturation", ["sum by (task_queue, worker_type) (temporal_worker_task_slots_used) / clamp_min(sum by (task_queue, worker_type) (temporal_worker_task_slots_used + temporal_worker_task_slots_available), 1)"]),
            ("Application queue backlog", ["max by (perimeter, task_type) (ai_temporal_task_queue_backlog)"]),
            ("Application queue pollers", ["max by (perimeter, task_type) (ai_temporal_task_queue_pollers)"]),
            ("Workflow task schedule-to-start p95", ['histogram_quantile(0.95, sum by (le) (rate({__name__="workflow_task_schedule_to_start_latency.bucket"}[5m])))']),
            ("SDK activity schedule-to-start p95", ['histogram_quantile(0.95, sum by (le, task_queue) (rate({__name__="temporal_activity_schedule_to_start_latency.bucket"}[5m])))']),
            ("Activity errors, retries and timeouts", ["sum by (activity_type) (rate(temporal_activity_execution_failed[5m]))", "sum by (perimeter) (rate(ai_temporal_activity_retries[5m]))", "sum by (perimeter) (rate(ai_temporal_timeouts[5m]))"]),
            ("Workflows waiting for a human", ["ai_temporal_workflows_waiting_human"]),
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


def panel(title: str, expressions: list[str], links: tuple[dict, ...] = OPERATIONAL_LINKS) -> dict:
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
            "links": list(links),
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
    links = definition.get("links", OPERATIONAL_LINKS)
    for index, (title, expressions) in enumerate(definition["panels"]):
        panel_id = str(uuid.uuid5(NAMESPACE, f"{slug}:{index}:{title}"))
        panels[panel_id] = panel(title, expressions, links)
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
        "tags": [{"key": "project", "value": "ai-factory"}, {"key": "domain", "value": slug}],
        "spec": {
            "display": {"name": definition["name"], "description": definition["description"]},
            "variables": [text_variable(*variable) for variable in SEARCH_VARIABLES + definition.get("variables", ())],
            "panels": panels,
            "layouts": [{"kind": "Grid", "spec": {"items": items}}],
            "duration": "6h",
            "refreshInterval": "30s",
            "links": list(links),
        },
    }


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for slug, definition in DASHBOARDS.items():
        path = OUTPUT / f"{slug}.json"
        path.write_text(json.dumps(dashboard(slug, definition), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
