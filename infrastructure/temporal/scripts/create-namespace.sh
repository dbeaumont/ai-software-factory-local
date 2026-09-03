#!/bin/sh

set -eu

namespace=${DEFAULT_NAMESPACE:-default}
retention=${DEFAULT_NAMESPACE_RETENTION:-7d}
temporal_address=${TEMPORAL_ADDRESS:-temporal:7233}

if temporal operator namespace describe --address "$temporal_address" \
  --namespace "$namespace" >/dev/null 2>&1; then
  echo "Temporal namespace '$namespace' already exists."
  exit 0
fi

temporal operator namespace create --address "$temporal_address" \
  --namespace "$namespace" --retention "$retention"
echo "Temporal namespace '$namespace' created."
