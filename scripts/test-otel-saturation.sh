#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
collector=ai-factory-otel-saturation-test
sink=ai-factory-otel-slow-sink-test
python_image=python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844
collector_image=otel/opentelemetry-collector-contrib:0.160.0@sha256:799dc6cf12c96192af37b5bdba804da8c10b3bc563b43cb90c3f3c58d9572ad6

cleanup() {
  docker rm -f "$collector" "$sink" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker run -d --name "$sink" --network ai-factory-signoz-network --read-only --tmpfs /tmp:size=8m \
  --cap-drop ALL --security-opt no-new-privileges --memory 64m --cpus 0.25 \
  -e OTLP_SINK_DELAY_SECONDS=2 \
  -v "$PWD/scripts/slow-otlp-sink.py:/opt/slow-otlp-sink.py:ro" \
  "$python_image" python /opt/slow-otlp-sink.py >/dev/null

docker run -d --name "$collector" --network ai-factory-signoz-network --read-only --tmpfs /tmp:size=16m \
  --cap-drop ALL --security-opt no-new-privileges --memory 128m --cpus 0.5 \
  -v "$PWD/infrastructure/observability/otel-collector-saturation-test.yaml:/etc/otelcol/config.yaml:ro" \
  "$collector_image" --config=/etc/otelcol/config.yaml >/dev/null

for attempt in {1..30}; do
  docker logs "$collector" 2>&1 | grep -q 'Everything is ready' && break
  [ "$attempt" -lt 30 ] || { echo "Saturation test Collector did not become ready" >&2; exit 1; }
  sleep 1
done

docker run --rm --network ai-factory-signoz-network \
  -v "$PWD/scripts/otel-load-generator.py:/opt/load.py:ro" \
  "$python_image" python /opt/load.py --endpoint "http://$collector:4318" --batches 300 --points 2 --workers 32 >/dev/null
sleep 2

logs=$(docker logs "$collector" 2>&1)
printf '%s' "$logs" | grep -Eq 'sending queue is full|sending_queue is full|Too Many Requests|non-retryable error' || {
  echo "Collector did not report the expected bounded saturation" >&2
  exit 1
}
docker inspect "$collector" --format '{{.State.Running}}' | grep -qx true
echo "Bounded saturation verified with a slow backend and an isolated eight-element queue."
