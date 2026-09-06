#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

output=${1:-.local/a2a-pki}
if [ "$output" = "/" ] || [ "$output" = "." ] || [ -z "$output" ]; then
  echo "Refusing unsafe A2A PKI output path: $output" >&2
  exit 2
fi
if [ -e "$output" ]; then
  echo "A2A local PKI already exists at $output; refusing to overwrite it." >&2
  exit 2
fi

parent=$(dirname "$output")
mkdir -p "$parent"
stage=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-pki.XXXXXX")
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/authority/newcerts" "$stage/workloads"
touch "$stage/authority/index.txt"
printf '1000\n' > "$stage/authority/serial"
printf '1000\n' > "$stage/authority/crlnumber"

cat > "$stage/authority/openssl.cnf" <<'EOF'
[ ca ]
default_ca = local_ca

[ local_ca ]
dir = .
database = $dir/index.txt
new_certs_dir = $dir/newcerts
certificate = $dir/ca.crt
private_key = $dir/ca.key
serial = $dir/serial
crlnumber = $dir/crlnumber
default_md = sha256
default_days = 30
default_crl_days = 7
policy = service_policy
unique_subject = no
copy_extensions = copy
x509_extensions = workload_ext

[ service_policy ]
commonName = supplied

[ workload_ext ]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
EOF

cat > "$stage/authority/ca-request.cnf" <<'EOF'
[ req ]
prompt = no
distinguished_name = dn
x509_extensions = ca_ext

[ dn ]
CN = AI Factory local A2A development CA
O = AI Software Factory

[ ca_ext ]
basicConstraints = critical,CA:true,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF

openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -sha256 \
  -config "$stage/authority/ca-request.cnf" \
  -keyout "$stage/authority/ca.key" -out "$stage/authority/ca.crt" >/dev/null 2>&1

issue_certificate() {
  local identity="$1"
  local client_id="$2"
  local spiffe_id="$3"
  local dns_name="$4"
  local directory="$stage/workloads/$identity"
  mkdir -p "$directory"
  cat > "$directory/request.cnf" <<EOF
[ req ]
prompt = no
distinguished_name = dn
req_extensions = san

[ dn ]
CN = $client_id
O = AI Software Factory

[ san ]
subjectAltName = @names

[ names ]
URI.1 = $spiffe_id
DNS.1 = $dns_name
EOF
  openssl req -new -newkey rsa:2048 -nodes -sha256 -config "$directory/request.cnf" \
    -keyout "$directory/tls.key" -out "$directory/tls.csr" >/dev/null 2>&1
  (cd "$stage/authority" && openssl ca -batch -config openssl.cnf \
    -in "../workloads/$identity/tls.csr" -out "../workloads/$identity/tls.crt") >/dev/null 2>&1
  cp "$stage/authority/ca.crt" "$directory/ca.crt"
  rm "$directory/tls.csr" "$directory/request.cnf"
}

issue_certificate orchestrator ai-factory-orchestrator \
  spiffe://ai-factory.local/control/orchestrator orchestrator
issue_certificate a2a-identity ai-factory-a2a-identity \
  spiffe://ai-factory.local/control/a2a-identity a2a-identity

roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
for role in "${roles[@]}"; do
  issue_certificate "$role" "ai-factory-agent-$role" \
    "spiffe://ai-factory.local/agent/$role" "a2a-$role"
done

(cd "$stage/authority" && openssl ca -config openssl.cnf -gencrl -out ca.crl) >/dev/null 2>&1
keytool -importcert -noprompt -storetype PKCS12 -storepass changeit \
  -alias ai-factory-a2a-ca -file "$stage/authority/ca.crt" \
  -keystore "$stage/authority/truststore.p12" >/dev/null 2>&1
for directory in "$stage"/workloads/*; do
  cp "$stage/authority/ca.crl" "$directory/ca.crl"
  cp "$stage/authority/truststore.p12" "$directory/truststore.p12"
done

chmod 600 "$stage/authority/ca.key" "$stage"/workloads/*/tls.key
chmod 644 "$stage/authority/ca.crt" "$stage/authority/ca.crl" "$stage"/workloads/*/*.crt "$stage"/workloads/*/*.crl
chmod 644 "$stage/authority/truststore.p12" "$stage"/workloads/*/truststore.p12
mv "$stage" "$output"
trap - EXIT
echo "Generated role-isolated local A2A PKI at $output"
