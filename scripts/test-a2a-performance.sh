#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
export A2A_INTEGRATION_PROJECT="ai-factory-a2a-perf-$$"
compose=(docker compose -p "$A2A_INTEGRATION_PROJECT" --env-file .env -f infrastructure/compose.yaml
  -f infrastructure/a2a/compose-integration.yaml -f infrastructure/a2a/compose-performance.yaml
  --profile a2a-developer)
result=$(mktemp)
load_image=python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$result"
}
trap cleanup EXIT

"${compose[@]}" up --build --detach a2a-task-db a2a-developer
deadline=$((SECONDS + 180))
until [ "$("${compose[@]}" ps --format json a2a-developer | jq -sr '.[0].Health // empty')" = healthy ]; do
  (( SECONDS < deadline )) || { "${compose[@]}" logs a2a-developer >&2; exit 1; }
  sleep 2
done

container=$("${compose[@]}" ps -q a2a-developer)
docker run --rm --network "${A2A_INTEGRATION_PROJECT}-a2a" --read-only \
  --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges \
  --memory 128m --cpus 1 \
  -v "$PWD/scripts/a2a-performance-load.py:/opt/a2a-performance-load.py:ro" \
  "$load_image" python /opt/a2a-performance-load.py "http://a2a-developer:8090/a2a" \
  --requests "${A2A_PERFORMANCE_REQUESTS:-300}" \
  --concurrency "${A2A_PERFORMANCE_CONCURRENCY:-16}" \
  --saturation-requests "${A2A_SATURATION_REQUESTS:-64}" \
  --max-active 32 --max-p95-ms "${A2A_MAX_P95_MS:-1000}" \
  --min-throughput "${A2A_MIN_THROUGHPUT_RPS:-20}" >"$result"

memory_mib=$(docker stats --no-stream --format '{{json .}}' "$container" | python3 -c '
import json, re, sys
value=json.load(sys.stdin)["MemUsage"].split("/")[0].strip()
number=float(re.match(r"[0-9.]+", value).group())
unit=re.sub(r"[0-9. ]", "", value)
factors={"B":1/(1024*1024),"kB":1/1024,"KiB":1/1024,"MB":1,"MiB":1,"GB":1024,"GiB":1024}
print(round(number*factors[unit], 3))')
python3 - "$result" "$memory_mib" <<'PY' | tee docs/evidence/a2a/A2A-169-PERFORMANCE.json
import json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    result=json.load(source)
result["memory"]={"resident_mib_after_load":float(sys.argv[2]),"limit_mib":768}
assert result["memory"]["resident_mib_after_load"] < 768
print(json.dumps(result, indent=2, sort_keys=True))
PY
