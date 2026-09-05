#!/bin/sh

set -eu

namespace=${DEFAULT_NAMESPACE:-default}
retention=${DEFAULT_NAMESPACE_RETENTION:-7d}
temporal_address=${TEMPORAL_ADDRESS:-temporal:7233}

if temporal operator namespace describe --address "$temporal_address" \
  --namespace "$namespace" >/dev/null 2>&1; then
  echo "Temporal namespace '$namespace' already exists."
else
  temporal operator namespace create --address "$temporal_address" \
    --namespace "$namespace" --retention "$retention"
  echo "Temporal namespace '$namespace' created."
fi

attributes=$(temporal operator search-attribute list --address "$temporal_address" \
  --namespace "$namespace" --output json)
while IFS='|' read -r name type; do
  case "$name" in ''|'#'*) continue ;; esac
  if printf '%s' "$attributes" | grep -Fq "\"$name\""; then
    echo "Temporal search attribute '$name' already exists."
  else
    temporal operator search-attribute create --address "$temporal_address" \
      --namespace "$namespace" --name "$name" --type "$type"
    echo "Temporal search attribute '$name' created."
  fi
done < /opt/temporal/search-attributes-v1.txt
