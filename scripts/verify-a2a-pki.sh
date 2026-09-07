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
  local truststore="$pki/workloads/$identity/truststore.p12"

  test -s "$certificate" || { echo "Missing certificate for $identity" >&2; exit 1; }
  test -s "$private_key" || { echo "Missing private key for $identity" >&2; exit 1; }
  test -s "$truststore" || { echo "Missing truststore for $identity" >&2; exit 1; }
  test ! -L "$private_key" && test "$(mode_of "$private_key")" = "600" || {
    echo "A2A private key must be a mode 0600 regular file: $private_key" >&2
    exit 1
  }
  openssl x509 -checkend "$minimum_seconds" -noout -in "$certificate" >/dev/null
  openssl verify -CAfile "$ca" -CRLfile "$crl" -crl_check \
    -purpose sslclient "$certificate" >/dev/null
  openssl verify -CAfile "$ca" -CRLfile "$crl" -crl_check \
    -purpose sslserver "$certificate" >/dev/null

  # macOS ships LibreSSL, whose `verify` command has no `-verify_hostname` option and whose `x509` command has
  # no `-ext` selector. Decode the certificate once and compare complete, comma-delimited SAN entries so the
  # same verification remains strict with both LibreSSL and OpenSSL 3.
  local certificate_text subject_alt_names
  certificate_text=$(openssl x509 -in "$certificate" -noout -text)
  subject_alt_names=$(printf '%s\n' "$certificate_text" \
    | sed -n '/Subject Alternative Name/{n;p;}' \
    | tr ',' '\n' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  printf '%s\n' "$subject_alt_names" | grep -Fxq "DNS:$dns_name" || {
    echo "Certificate DNS SAN does not match $identity: expected $dns_name" >&2
    exit 1
  }
  printf '%s\n' "$subject_alt_names" | grep -Fxq "URI:$spiffe_id" || {
    echo "Certificate SPIFFE SAN does not match $identity: expected $spiffe_id" >&2
    exit 1
  }

  local public_from_certificate public_from_key
  public_from_certificate=$(openssl x509 -in "$certificate" -pubkey -noout | openssl sha256)
  public_from_key=$(openssl pkey -in "$private_key" -pubout 2>/dev/null | openssl sha256)
  test "$public_from_certificate" = "$public_from_key" || {
    echo "Certificate and private key do not match for $identity" >&2
    exit 1
  }
}

verify_identity orchestrator spiffe://ai-factory.local/control/orchestrator orchestrator
verify_identity a2a-identity spiffe://ai-factory.local/control/a2a-identity a2a-identity
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
for role in "${roles[@]}"; do
  verify_identity "$role" "spiffe://ai-factory.local/agent/$role" "a2a-$role"
done

echo "A2A PKI verified: trusted chains, SANs, validity, key pairs and CRL status are valid."
