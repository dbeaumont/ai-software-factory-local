#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
database=${ORCHESTRATOR_DB_NAME:-ai_factory}
user=${ORCHESTRATOR_DB_USER:-ai_factory}
mode=${1:-status}

case "$mode" in
  close)
    sql="UPDATE factory_admission_control SET admissions_open = false, reason = 'temporal_cutover', revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE control_key = 'global';"
    ;;
  open)
    sql="UPDATE factory_admission_control SET admissions_open = true, reason = 'normal_operation', revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE control_key = 'global';"
    ;;
  status)
    sql="SELECT admissions_open, reason, revision, updated_at FROM factory_admission_control WHERE control_key = 'global';"
    ;;
  *)
    echo "Usage: $0 {close|open|status}" >&2
    exit 2
    ;;
esac

"${compose[@]}" exec -T orchestrator-db psql -v ON_ERROR_STOP=1 -U "$user" -d "$database" -c "$sql"

if [ "$mode" != status ]; then
  "${compose[@]}" exec -T orchestrator-db psql -v ON_ERROR_STOP=1 -U "$user" -d "$database" -c \
    "SELECT admissions_open, reason, revision, updated_at FROM factory_admission_control WHERE control_key = 'global';"
fi
