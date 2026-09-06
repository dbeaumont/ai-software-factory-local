#!/bin/sh

set -eu

namespace=${DEFAULT_NAMESPACE:-ai-factory-local}
temporal_address=${TEMPORAL_ADDRESS:-temporal:7233}
deployment=${WORKER_DEPLOYMENT_NAME:-ai-factory-orchestrator}
build_id=${WORKER_BUILD_ID:-0.1.0}
expected_queues=${EXPECTED_TASK_QUEUES:-ai-factory-workflows,ai-factory-context,ai-factory-llm,ai-factory-sandbox,ai-factory-assurance,ai-factory-evidence,ai-factory-scm}

version=''
attempt=1
while [ "$attempt" -le 60 ]; do
  if version=$(temporal worker deployment describe-version --address "$temporal_address" \
      --namespace "$namespace" --deployment-name "$deployment" --build-id "$build_id" 2>/dev/null); then
    break
  fi
  attempt=$((attempt + 1))
  sleep 1
done

if [ -z "$version" ]; then
  echo "Temporal worker deployment version '$deployment:$build_id' was not registered." >&2
  exit 1
fi

old_ifs=$IFS
IFS=','
for queue in $expected_queues; do
  if ! printf '%s\n' "$version" | grep -Fq "$queue"; then
    echo "Temporal worker version is missing expected task queue '$queue'." >&2
    exit 1
  fi
done
IFS=$old_ifs

deployment_state=$(temporal worker deployment describe --address "$temporal_address" \
  --namespace "$namespace" --name "$deployment")
if printf '%s\n' "$deployment_state" | grep -Eq "CurrentVersionBuildID[[:space:]]+$build_id$"; then
  echo "Temporal worker deployment version '$deployment:$build_id' is already current."
  exit 0
fi

temporal worker deployment set-current-version --yes --address "$temporal_address" \
  --namespace "$namespace" --deployment-name "$deployment" --build-id "$build_id"
echo "Temporal worker deployment version '$deployment:$build_id' is current."
