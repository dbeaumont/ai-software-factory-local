#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

canary="otel-redaction-$(date +%s)"
timestamp="$(date +%s)000000000"
payload=$(jq -nc --arg canary "$canary" --arg timestamp "$timestamp" '
  {resourceLogs:[{resource:{attributes:[{key:"service.name",value:{stringValue:"otel-redaction-test"}}]},
    scopeLogs:[{scope:{name:"ai-factory-redaction-test"},logRecords:[{
      timeUnixNano:$timestamp,severityText:"INFO",body:{stringValue:$canary},attributes:[
        {key:"authorization",value:{stringValue:"OTEL-SENSITIVE-CANARY"}},
        {key:"gen_ai.prompt",value:{stringValue:"OTEL-SENSITIVE-CANARY"}},
        {key:"code",value:{stringValue:"OTEL-SENSITIVE-CANARY"}},
        {key:"patch",value:{stringValue:"OTEL-SENSITIVE-CANARY"}},
        {key:"url.query",value:{stringValue:"token=OTEL-SENSITIVE-CANARY"}},
        {key:"db.statement",value:{stringValue:"OTEL-SENSITIVE-CANARY"}},
        {key:"exception.message",value:{stringValue:"OTEL-SENSITIVE-CANARY"}}
      ]}]}]}]}')

docker exec ai-software-factory-orchestrator-1 curl -fsS \
  -X POST http://otel-collector:4318/v1/logs \
  -H 'Content-Type: application/json' --data-binary "$payload" >/dev/null

for attempt in {1..15}; do
  rows=$(docker exec ai-factory-signoz-telemetrystore-clickhouse-0-0 clickhouse-client --query \
    "SELECT count() FROM signoz_logs.logs_v2 WHERE body = '$canary'")
  [ "$rows" -gt 0 ] && break
  sleep 1
done
[ "${rows:-0}" -eq 1 ] || { echo "Expected exactly one redaction canary log, got ${rows:-0}" >&2; exit 1; }

leaked=$(docker exec ai-factory-signoz-telemetrystore-clickhouse-0-0 clickhouse-client --query \
  "SELECT count() FROM signoz_logs.logs_v2 WHERE body = '$canary' AND (mapContains(attributes_string, 'authorization') OR mapContains(attributes_string, 'gen_ai.prompt') OR mapContains(attributes_string, 'code') OR mapContains(attributes_string, 'patch') OR mapContains(attributes_string, 'url.query') OR mapContains(attributes_string, 'db.statement') OR mapContains(attributes_string, 'exception.message'))")
[ "$leaked" -eq 0 ] || { echo "Collector redaction leaked forbidden OTLP attributes" >&2; exit 1; }

echo "OpenTelemetry redaction verified with canary $canary"
