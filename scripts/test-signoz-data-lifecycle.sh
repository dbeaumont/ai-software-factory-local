#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
database="ai_factory_otel_lifecycle_$$"
[[ "$database" =~ ^ai_factory_otel_lifecycle_[0-9]+$ ]] || exit 2

cleanup() {
  "${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query "DROP DATABASE IF EXISTS $database" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" exec -T signoz-clickhouse clickhouse-client --multiquery --query "
  CREATE DATABASE $database;
  CREATE TABLE $database.events (
    recorded_at DateTime,
    marker LowCardinality(String)
  ) ENGINE = MergeTree ORDER BY (recorded_at, marker)
    TTL recorded_at + INTERVAL 1 HOUR DELETE;
  INSERT INTO $database.events VALUES (now() - INTERVAL 1 DAY, 'expired'), (now(), 'retained');
  ALTER TABLE $database.events MATERIALIZE TTL;
  OPTIMIZE TABLE $database.events FINAL;
" >/dev/null

expired=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
  "SELECT count() FROM $database.events WHERE marker = 'expired'")
retained=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query \
  "SELECT count() FROM $database.events WHERE marker = 'retained'")
[ "$expired" -eq 0 ] || { echo "Expired lifecycle fixture was not removed" >&2; exit 1; }
[ "$retained" -eq 1 ] || { echo "Retained lifecycle fixture disappeared" >&2; exit 1; }

"${compose[@]}" exec -T signoz-clickhouse clickhouse-client --multiquery --query "
  ALTER TABLE $database.events DELETE WHERE marker = 'retained';
  OPTIMIZE TABLE $database.events FINAL;
" >/dev/null
remaining=$("${compose[@]}" exec -T signoz-clickhouse clickhouse-client --query "SELECT count() FROM $database.events")
[ "$remaining" -eq 0 ] || { echo "Controlled deletion fixture remains" >&2; exit 1; }

echo "SigNoz ClickHouse lifecycle verified: TTL deletion, compaction and controlled deletion succeeded."
