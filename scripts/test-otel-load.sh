#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
generator_image=python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844
expected_records=10400
started_ms=$(($(date +%s) * 1000))

restore() {
  "${compose[@]}" start signoz-ingester otel-collector >/dev/null 2>&1 || true
}
trap restore EXIT

run_load() {
  docker run --rm --network ai-factory-signoz-network \
    -v "$PWD/scripts/otel-load-generator.py:/opt/load.py:ro" \
    "$generator_image" python /opt/load.py "$@"
}

run_load --batches 100 --points 20 --workers 16
run_load --batches 800 --points 10 --workers 16 &
load_pid=$!
"${compose[@]}" restart repository-context-mcp >/dev/null
wait "$load_pid"
for attempt in {1..30}; do
  health=$("${compose[@]}" ps --format json repository-context-mcp | jq -r '.Health // empty')
  [ "$health" = "healthy" ] && break
  [ "$attempt" -lt 30 ] || { echo "repository-context-mcp did not recover during load" >&2; exit 1; }
  sleep 1
done
sleep 10
metric_count=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
  "SELECT count() FROM signoz_metrics.distributed_metadata WHERE metric_name = 'ai_factory_otel_load_probe'")
[ "$metric_count" -gt 0 ] || { echo "Load probe metric was not ingested" >&2; exit 1; }

"${compose[@]}" stop signoz-ingester >/dev/null
run_load --batches 40 --points 10 --workers 8
queue_files=$(docker run --rm --network none -v ai-software-factory_otel-collector-queue:/queue:ro \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  find /queue -type f -size +0c | wc -l)
[ "$queue_files" -ge 3 ] || { echo "Persistent queues were not populated during outage" >&2; exit 1; }

"${compose[@]}" restart otel-collector >/dev/null
"${compose[@]}" start signoz-ingester >/dev/null
for attempt in {1..60}; do
  trace_count=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
    "SELECT count() FROM signoz_traces.distributed_signoz_index_v3 WHERE serviceName = 'otel-load-generator' AND timestamp >= fromUnixTimestamp64Milli($started_ms)")
  log_count=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
    "SELECT count() FROM signoz_logs.distributed_logs_v2 WHERE resources_string['service.name'] = 'otel-load-generator' AND timestamp >= $((started_ms * 1000000))")
  metric_count=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
    "SELECT count() FROM signoz_metrics.distributed_samples_v4 WHERE metric_name = 'ai_factory_otel_load_probe' AND unix_milli >= $started_ms")
  if [ "$trace_count" -ge "$expected_records" ] && [ "$log_count" -ge "$expected_records" ] && [ "$metric_count" -ge "$expected_records" ]; then
    break
  fi
  [ "$attempt" -lt 60 ] || {
    echo "Telemetry did not drain in 60s: metrics=$metric_count traces=$trace_count logs=$log_count" >&2
    exit 1
  }
  sleep 1
done
[ "$trace_count" -eq "$expected_records" ] || { echo "Trace loss or duplication: expected=$expected_records actual=$trace_count" >&2; exit 1; }
[ "$log_count" -eq "$expected_records" ] || { echo "Log loss or duplication: expected=$expected_records actual=$log_count" >&2; exit 1; }
[ "$metric_count" -eq "$expected_records" ] || { echo "Metric loss or duplication: expected=$expected_records actual=$metric_count" >&2; exit 1; }

metric_series=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
  "SELECT uniqExact(fingerprint) FROM signoz_metrics.distributed_samples_v4 WHERE metric_name = 'ai_factory_otel_load_probe' AND unix_milli >= $started_ms")
trace_ids=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
  "SELECT uniqExact(trace_id) FROM signoz_traces.distributed_signoz_index_v3 WHERE serviceName = 'otel-load-generator' AND timestamp >= fromUnixTimestamp64Milli($started_ms)")
ingestion_delay_ms=$(($(date +%s) * 1000 - started_ms))
./scripts/check-signoz-telemetry.sh >/dev/null

collector_id=$("${compose[@]}" ps -q otel-collector)
collector_memory=$(docker stats --no-stream --format '{{.MemUsage}}' "$collector_id")
collector_cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' "$collector_id")
queue_disk=$(docker run --rm --network none -v ai-software-factory_otel-collector-queue:/queue:ro \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  du -sk /queue | awk '{print $1}')
printf 'Collector load and persistent recovery verified: accepted_per_signal=%s delivered_per_signal=%s loss=0 duplicates=0 elapsed_ms=%s metric_series=%s trace_ids=%s collector_cpu=%s collector_memory=%s queue_disk_kib=%s local_cloud_cost_usd=0\n' \
  "$expected_records" "$metric_count" "$ingestion_delay_ms" "$metric_series" "$trace_ids" \
  "$collector_cpu" "$collector_memory" "$queue_disk"
