#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
[ -f .env ] && set -a && source .env && set +a

base_url=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
email=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"
now=$(date +%s)
start_ns=$(((now - 300) * 1000000000))
old_ns=$(((now - 150) * 1000000000))
reset_ns=$(((now - 90) * 1000000000))
current_ns=$(((now - 30) * 1000000000))

payload=$(jq -nc \
  --arg start "$start_ns" --arg old "$old_ns" --arg reset "$reset_ns" --arg current "$current_ns" '
  def a($key;$value): {key:$key,value:{stringValue:$value}};
  def p($start;$time;$value): {startTimeUnixNano:$start,timeUnixNano:$time,asDouble:$value,attributes:[]};
  def counter($name;$points): {name:$name,sum:{aggregationTemporality:2,isMonotonic:true,dataPoints:$points}};
  def h($start;$time;$counts;$count;$sum): {
    startTimeUnixNano:$start,timeUnixNano:$time,count:$count,sum:$sum,
    bucketCounts:$counts,explicitBounds:[0.1,0.5,1,2,5],attributes:[]
  };
  {resourceMetrics:[{
    resource:{attributes:[a("service.name";"otel-metric-semantics"),a("service.namespace";"ai-software-factory")]},
    scopeMetrics:[{scope:{name:"ai-factory-parity",version:"1"},metrics:[
      counter("ai_factory_parity_throughput";[p($start;$old;0),p($start;$current;100)]),
      counter("ai_factory_parity_errors";[p($start;$old;0),p($start;$current;5)]),
      counter("ai_factory_parity_reset";[p($start;$old;10),p($reset;$reset;2),p($reset;$current;7)]),
      {name:"ai_factory_parity_duration",unit:"s",histogram:{aggregationTemporality:2,dataPoints:[
        h($start;$old;["0","0","0","0","0","0"];"0";0),
        h($start;$current;["50","40","9","1","0","0"];"100";30)
      ]}}
    ]}]
  }]}' )

"${compose[@]}" exec -T orchestrator curl -fsS \
  -X POST http://otel-collector:4318/v1/metrics \
  -H 'Content-Type: application/json' --data-binary "$payload" >/dev/null

context=$(curl -fsS --get "$base_url/api/v2/sessions/context" --data-urlencode "email=$email" --data-urlencode "ref=$base_url")
org_id=$(printf '%s' "$context" | jq -er '.data.orgs[0].id')
login=$(jq -nc --arg email "$email" --arg password "$SIGNOZ_ROOT_PASSWORD" --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
token=$(curl -fsS -X POST "$base_url/api/v2/sessions/email_password" -H 'Content-Type: application/json' --data "$login" | jq -er '.data.accessToken')
trap 'curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT

range_start=$(((now - 330) * 1000))
range_end=$((now * 1000))
query_value() {
  local expression=$1
  local request response
  request=$(jq -nc --arg query "$expression" --argjson start "$range_start" --argjson end "$range_end" \
    '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:30}}]}}')
  response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$request")
  printf '%s' "$response" | jq -er '[.data.data.results[].aggregations[].series[].values[].value] | last'
}

assert_close() {
  local name=$1 actual=$2 expected=$3 tolerance=$4
  awk -v name="$name" -v actual="$actual" -v expected="$expected" -v tolerance="$tolerance" 'BEGIN {
    delta = actual - expected; if (delta < 0) delta = -delta;
    if (delta > tolerance) {printf "%s outside tolerance: actual=%s expected=%s tolerance=%s\n", name, actual, expected, tolerance > "/dev/stderr"; exit 1}
  }'
}

for attempt in {1..30}; do
  throughput=$(query_value 'max_over_time(ai_factory_parity_throughput[5m]) - min_over_time(ai_factory_parity_throughput[5m])' 2>/dev/null || true)
  [ -n "$throughput" ] && break
  sleep 1
done
[ -n "$throughput" ] || { echo "Synthetic parity metrics were not ingested" >&2; exit 1; }
errors=$(query_value 'max_over_time(ai_factory_parity_errors[5m]) - min_over_time(ai_factory_parity_errors[5m])')
p50=$(query_value 'histogram_quantile(0.50, sum by (le) (increase({__name__="ai_factory_parity_duration.bucket"}[5m])))')
p95=$(query_value 'histogram_quantile(0.95, sum by (le) (increase({__name__="ai_factory_parity_duration.bucket"}[5m])))')
p99=$(query_value 'histogram_quantile(0.99, sum by (le) (increase({__name__="ai_factory_parity_duration.bucket"}[5m])))')
resets=$(query_value 'resets(ai_factory_parity_reset[5m])')
no_data=$(query_value 'absent_over_time(ai_factory_intentionally_absent[5m])')

assert_close throughput "$throughput" 100 0.01
assert_close errors "$errors" 5 0.01
assert_close p50 "$p50" 0.1 0.001
assert_close p95 "$p95" 0.7777777778 0.01
assert_close p99 "$p99" 1 0.01
awk -v resets="$resets" 'BEGIN {if (resets < 1) {print "Counter reset was not detected" > "/dev/stderr"; exit 1}}'
assert_close no_data "$no_data" 1 0.01

printf 'SigNoz numeric semantics verified: throughput=%s errors=%s p50=%s p95=%s p99=%s resets=%s no_data=%s\n' \
  "$throughput" "$errors" "$p50" "$p95" "$p99" "$resets" "$no_data"
