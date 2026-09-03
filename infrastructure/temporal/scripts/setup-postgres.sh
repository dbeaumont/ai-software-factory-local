#!/bin/sh

set -eu

: "${POSTGRES_SEEDS:?POSTGRES_SEEDS is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${SQL_PASSWORD:?SQL_PASSWORD is required}"

db_port=${DB_PORT:-5432}

echo "Waiting for PostgreSQL..."
nc -z -w 10 "$POSTGRES_SEEDS" "$db_port"

setup_database() {
  database=$1
  schema_directory=$2

  temporal-sql-tool --plugin postgres12 --ep "$POSTGRES_SEEDS" \
    -u "$POSTGRES_USER" -p "$db_port" --db "$database" create
  temporal-sql-tool --plugin postgres12 --ep "$POSTGRES_SEEDS" \
    -u "$POSTGRES_USER" -p "$db_port" --db "$database" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep "$POSTGRES_SEEDS" \
    -u "$POSTGRES_USER" -p "$db_port" --db "$database" update-schema -d "$schema_directory"
}

setup_database temporal /etc/temporal/schema/postgresql/v12/temporal/versioned
setup_database temporal_visibility /etc/temporal/schema/postgresql/v12/visibility/versioned

echo "Temporal PostgreSQL schemas are ready."
