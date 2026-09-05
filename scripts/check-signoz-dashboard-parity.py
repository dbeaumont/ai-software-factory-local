#!/usr/bin/env python3
"""Check the versioned SigNoz dashboards against the captured Grafana baseline."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "docs/evidence/observability/prometheus-grafana-baseline-2026-09-05.json"
DASHBOARDS = ROOT / "infrastructure/observability/signoz/dashboards"
REQUIRED_VARIABLES = {"task_id", "role", "operation", "outcome", "model", "mcp_server"}
REQUIRED_LINKS = {"Search traces", "Search logs", "Temporal UI", "Task API", "Observability runbooks"}


def normalize(expression: str) -> str:
    replacements = {
        "http_server_requests_seconds_count": "http.server.requests.count",
        "ai_agent_duration_seconds_bucket": "ai_agent_duration.bucket",
        "ai_agent_duration_seconds_count": "ai_agent_duration.count",
        "mcp_client_duration_seconds_bucket": "mcp_client_duration.bucket",
        "ai_factory_sandbox_job_queue_duration_seconds_bucket": "ai_factory_sandbox_job_queue_duration.bucket",
        "workflow_task_schedule_to_start_latency_bucket": "workflow_task_schedule_to_start_latency.bucket",
        "ai_delegation_fan_out_max": "ai_delegation_fan_out.max",
        "ai_delegation_depth_max": "ai_delegation_depth.max",
        "_total": "",
        "persistence_errors": "persistence_error_with_type",
    }
    result = expression
    for source, target in replacements.items():
        result = result.replace(source, target)
    result = re.sub(
        r'\{__name__="([^"]+)"([^}]*)\}',
        lambda match: match.group(1)
        + ("{" + match.group(2).lstrip(",") + "}" if match.group(2).lstrip(",") else ""),
        result,
    )
    return "".join(result.split())


def expected(expression: str) -> str:
    normalized = normalize(expression)
    equivalent_forms = {
        normalize(
            "histogram_quantile(0.95, sum(rate(ai_factory_sandbox_job_queue_duration_seconds_bucket[5m])) by (le))"
        ): normalize(
            "histogram_quantile(0.95, sum by (le) (rate(ai_factory_sandbox_job_queue_duration.bucket[5m])))"
        ),
        normalize(
            "histogram_quantile(0.95, sum(rate(workflow_task_schedule_to_start_latency_bucket[5m])) by (le))"
        ): normalize(
            "histogram_quantile(0.95, sum by (le) (rate(workflow_task_schedule_to_start_latency.bucket[5m])))"
        ),
    }
    return equivalent_forms.get(normalized, normalized)


def main() -> None:
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    failures: list[str] = []
    for legacy in baseline["dashboards"]:
        slug = Path(legacy["file"]).stem
        target = json.loads((DASHBOARDS / f"{slug}.json").read_text(encoding="utf-8"))
        spec = target["spec"]
        if spec["display"]["name"] != legacy["title"]:
            failures.append(f"{slug}: title differs")
        target_queries = {
            normalize(query["spec"]["query"])
            for panel in spec["panels"].values()
            for wrapper in panel["spec"]["queries"]
            for query in wrapper["spec"]["plugin"]["spec"]["queries"]
        }
        missing_queries = [query for query in legacy["expressions"] if expected(query) not in target_queries]
        if missing_queries:
            failures.append(f"{slug}: missing normalized queries: {missing_queries}")
        variables = {variable["spec"]["name"] for variable in spec["variables"]}
        if variables != REQUIRED_VARIABLES:
            failures.append(f"{slug}: search variables differ: {sorted(variables)}")
        links = {link["name"] for link in spec["links"]}
        if links != REQUIRED_LINKS:
            failures.append(f"{slug}: operational links differ: {sorted(links)}")
        if spec["duration"] != "6h" or spec["refreshInterval"] != "30s":
            failures.append(f"{slug}: time window or refresh differs")
        for item in spec["panels"].values():
            plugin = item["spec"]["plugin"]["spec"]
            if plugin["formatting"]["unit"] != "none" or plugin["thresholds"] is not None:
                failures.append(f"{slug}: legacy neutral unit/threshold semantics differ")
            if {link["name"] for link in item["spec"]["links"]} != REQUIRED_LINKS:
                failures.append(f"{slug}: a panel lacks operational links")
    if failures:
        raise SystemExit("\n".join(failures))
    print("Six Grafana baselines match SigNoz queries, presentation defaults, search variables and links.")


if __name__ == "__main__":
    main()
