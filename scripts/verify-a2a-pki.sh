#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

pki=${1:-.local/a2a-pki}
ca="$pki/authority/ca.crt"
crl="$pki/authority/ca.crl"
minimum_seconds=${AI_FACTORY_A2A_TLS_MINIMUM_VALIDITY_SECONDS:-86400}

mode_of() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

test -s "$ca" || { echo "Missing A2A trust anchor: $ca" >&2; exit 1; }
test -s "$crl" || { echo "Missing A2A certificate revocation list: $crl" >&2; exit 1; }
test ! -L "$pki/authority/ca.key" && test "$(mode_of "$pki/authority/ca.key")" = "600" || {
  echo "A2A CA private key must be a mode 0600 regular file" >&2
  exit 1
}
openssl verify -CAfile "$ca" "$ca" >/dev/null
openssl crl -in "$crl" -noout -nextupdate >/dev/null

verify_identity() {
  local identity="$1"
  local spiffe_id="$2"
  local dns_name="$3"
  local certificate="$pki/workloads/$identity/tls.crt"
  local private_key="$pki/workloads/$identity/tls.key"

  test -s "$certificate" || { echo "Missing certificate for $identity" >&2; exit 1; }
  test -s "$private_key" || { echo "Missing private key for $identity" >&2; exit 1; }
  test ! -L "$private_key" && test "$(mode_of "$private_key")" = "600" || {
    echo "A2A private key must be a mode 0600 regular file: $private_key" >&2
    exit 1
  }
  openssl x509 -checkend "$minimum_seconds" -noout -in "$certificate" >/dev/null
  openssl verify -CAfile "$ca" -CRLfile "$crl" -crl_check \
    -purpose sslclient "$certificate" >/dev/null
  openssl verify -CAfile "$ca" -CRLfile "$crl" -crl_check \
    -purpose sslserver -verify_hostname "$dns_name" "$certificate" >/dev/null
  openssl x509 -in "$certificate" -noout -ext subjectAltName | grep -Fq "URI:$spiffe_id"

  local public_from_certificate public_from_key
  public_from_certificate=$(openssl x509 -in "$certificate" -pubkey -noout | openssl sha256)
  public_from_key=$(openssl pkey -in "$private_key" -pubout 2>/dev/null | openssl sha256)
  test "$public_from_certificate" = "$public_from_key" || {
    echo "Certificate and private key do not match for $identity" >&2
    exit 1
  }
}

verify_identity orchestrator spiffe://ai-factory.local/control/orchestrator orchestrator
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
for role in "${roles[@]}"; do
  verify_identity "$role" "spiffe://ai-factory.local/agent/$role" "a2a-$role"
done

echo "A2A PKI verified: trusted chains, SANs, validity, key pairs and CRL status are valid."
