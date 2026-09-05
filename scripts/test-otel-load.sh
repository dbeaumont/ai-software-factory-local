#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
generator_image=python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844

restore() {
  "${compose[@]}" start ingester otel-collector >/dev/null 2>&1 || true
}
trap restore EXIT

run_load() {
  docker run --rm --network ai-factory-signoz-network \
    -v "$PWD/scripts/otel-load-generator.py:/opt/load.py:ro" \
    "$generator_image" python /opt/load.py "$@"
}

run_load --batches 100 --points 20 --workers 16
sleep 10
metric_count=$(docker exec ai-factory-signoz-telemetrystore-clickhouse-0-0 clickhouse-client --query \
  "SELECT count() FROM signoz_metrics.distributed_metadata WHERE metric_name = 'ai_factory_otel_load_probe'")
[ "$metric_count" -gt 0 ] || { echo "Load probe metric was not ingested" >&2; exit 1; }

"${compose[@]}" stop ingester >/dev/null
run_load --batches 40 --points 10 --workers 8
queue_files=$(docker run --rm --network none -v ai-software-factory_otel-collector-queue:/queue:ro \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  find /queue -type f -size +0c | wc -l)
[ "$queue_files" -ge 3 ] || { echo "Persistent queues were not populated during outage" >&2; exit 1; }

"${compose[@]}" restart otel-collector >/dev/null
"${compose[@]}" start ingester >/dev/null
sleep 15
./scripts/check-signoz-telemetry.sh >/dev/null

collector_memory=$(docker stats --no-stream --format '{{.MemUsage}}' ai-software-factory-otel-collector-1)
echo "Collector load and persistent recovery verified; memory=$collector_memory"
