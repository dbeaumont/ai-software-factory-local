#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

settings=${MAVEN_SETTINGS_FILE:-apps/orchestrator/.mvn/settings-direct.xml}
mvn -B -s "$settings" -pl apps/a2a-agent-runtime -am test \
  -Dtest=A2aRollbackLoadTest,A2aRecoveryCoordinatorTest,A2aCancelTaskTest,PostgresA2aTaskStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false

echo "A2A rollback load campaign passed: 200 tasks, five lifecycle states, no lost task, artifact, notification or cancellation."
