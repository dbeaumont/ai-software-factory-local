#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_tag="v1.0.0"
sdk_commit="24db37ee24c927df936289ad6ffbc8c746a44db8"
reference_image="ai-factory/a2a-python-reference:${sdk_commit:0:12}"
project_client_image="${AI_FACTORY_ORCHESTRATOR_IMAGE:-ai-factory-orchestrator:latest}"
project_server_image="${AI_FACTORY_A2A_RUNTIME_IMAGE:-ai-factory-a2a-agent-runtime:0.1.0}"
network="ai-factory-a2a-interop-$$-${RANDOM}"
checkout="$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-interop.XXXXXX")"
reference="a2a-reference-$$-${RANDOM}"
project_server="a2a-project-server-$$-${RANDOM}"
evidence="${root}/docs/evidence/a2a/A2A-163-INTEROPERABILITY.log"

cleanup() {
  docker rm -f "${reference}" >/dev/null 2>&1 || true
  docker rm -f "${project_server}" >/dev/null 2>&1 || true
  docker network rm "${network}" >/dev/null 2>&1 || true
  rm -rf "${checkout}"
}
trap cleanup EXIT

mkdir -p "${checkout}/a2a-python"
git clone --quiet --depth 1 --branch "${sdk_tag}" \
  https://github.com/a2aproject/a2a-python.git "${checkout}/a2a-python"
[[ "$(git -C "${checkout}/a2a-python" rev-parse HEAD)" == "${sdk_commit}" ]] || {
  echo "Official Python SDK revision mismatch" >&2
  exit 1
}

card_jwks="${root}/.local/a2a-secrets/roles/developer/card-jwks.json"
[[ -f "${card_jwks}" ]] || {
  echo "Missing ${card_jwks}; run scripts/generate-a2a-local-secrets.sh first" >&2
  exit 1
}

# Dependency downloads happen only while building the public reference image.
docker build \
  --file "${root}/infrastructure/a2a/interop/Dockerfile.reference" \
  --tag "${reference_image}" \
  "${checkout}" >/dev/null

# Both directions execute afterwards on an ephemeral network with no external route.
docker network create --internal "${network}" >/dev/null
docker run --detach --name "${reference}" \
  --network "${network}" --network-alias a2a-reference \
  --read-only --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -e REFERENCE_HOST=a2a-reference:9999 \
  -v "${root}/infrastructure/a2a/interop/reference_server.py:/reference_server.py:ro" \
  "${reference_image}" /reference_server.py >/dev/null
docker run --detach --name "${project_server}" \
  --network "${network}" --network-alias a2a-project-server \
  --read-only --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -e AI_FACTORY_AGENT_ROLE=developer \
  -e AI_FACTORY_AGENT_ENDPOINT=http://a2a-project-server:8090/a2a \
  -e AI_FACTORY_A2A_TCK_ENABLED=true \
  -e AI_FACTORY_A2A_CARD_JWK_SET_PATH=/run/secrets/a2a-card-jwks \
  -e AI_FACTORY_A2A_CARD_ACTIVE_KID=a2a-developer-local-v1 \
  -v "${card_jwks}:/run/secrets/a2a-card-jwks:ro" \
  "${project_server_image}" >/dev/null

for _ in $(seq 1 30); do
  reference_ready="$(docker exec "${reference}" /workspace/a2a-python/.venv/bin/python \
    -c "import socket; socket.create_connection(('127.0.0.1', 9999), 1).close()" 2>/dev/null && echo yes || true)"
  project_ready="$(docker inspect --format '{{.State.Health.Status}}' "${project_server}" 2>/dev/null || true)"
  [[ "${reference_ready}" == "yes" && "${project_ready}" == "healthy" ]] && break
  sleep 1
done
[[ "${reference_ready:-}" == "yes" ]] || {
  docker logs "${reference}"
  echo "Official reference server did not become ready" >&2
  exit 1
}
[[ "${project_ready:-}" == "healthy" ]] || {
  docker logs "${project_server}"
  echo "Project A2A server did not become healthy" >&2
  exit 1
}

mkdir -p "$(dirname "${evidence}")"
{
  echo "A2A interoperability qualification"
  echo "protocol=1.0"
  echo "python-sdk=${sdk_tag}@${sdk_commit}"
  docker run --rm --network "${network}" \
    --read-only --tmpfs /tmp:size=32m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges:true \
    --entrypoint java "${project_client_image}" \
    -Dloader.main=com.example.aifactory.a2a.A2aInteropProbe \
    -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
    http://a2a-reference:9999
  docker run --rm --network "${network}" \
    --read-only --tmpfs /tmp:size=32m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges:true \
    -v "${root}/infrastructure/a2a/interop/official_client_probe.py:/probe.py:ro" \
    --entrypoint /workspace/a2a-python/.venv/bin/python \
    "${reference_image}" /probe.py http://a2a-project-server:8090
} | tee "${evidence}"
