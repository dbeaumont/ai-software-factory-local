#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
image=${1:?usage: qualify-a2a-runtime-image.sh IMAGE@sha256:DIGEST [OUTPUT_DIR]}
output=${2:-.local/a2a-supply-chain}
identity=${COSIGN_CERTIFICATE_IDENTITY_REGEXP:-'^https://github.com/.+/.github/workflows/.+@refs/(heads/main|tags/.+)$'}
issuer=${COSIGN_CERTIFICATE_OIDC_ISSUER:-https://token.actions.githubusercontent.com}

[[ "$image" =~ @sha256:[a-f0-9]{64}$ ]] || {
  echo "A2A runtime qualification requires an immutable image digest" >&2
  exit 2
}
for command in syft trivy cosign; do
  command -v "$command" >/dev/null || { echo "Required supply-chain tool is unavailable: $command" >&2; exit 2; }
done

mkdir -p "$output"
syft "$image" -o "cyclonedx-json=$output/sbom.cdx.json" -o "spdx-json=$output/sbom.spdx.json"
./scripts/verify-a2a-supply-chain-policy.rb resources/a2a/supply-chain-policy-v1.json \
  "$output/sbom.cdx.json" | ruby -rjson -e 'puts JSON.generate({status: "PASSED", detail: STDIN.read.strip})' \
  > "$output/license-report.json"
trivy image --scanners vuln,secret --severity HIGH,CRITICAL --ignore-unfixed=false \
  --exit-code 1 --format json --output "$output/trivy.json" "$image"
cosign verify --certificate-identity-regexp "$identity" --certificate-oidc-issuer "$issuer" \
  --output json "$image" > "$output/signature-verification.json"
cosign verify-attestation --type slsaprovenance --certificate-identity-regexp "$identity" \
  --certificate-oidc-issuer "$issuer" --output json "$image" > "$output/provenance-verification.json"

for artifact in sbom.cdx.json sbom.spdx.json trivy.json license-report.json \
  signature-verification.json provenance-verification.json; do
  openssl dgst -sha256 "$output/$artifact"
done > "$output/manifest.sha256"

echo "A2A runtime supply chain qualified for $image; evidence written to $output"
