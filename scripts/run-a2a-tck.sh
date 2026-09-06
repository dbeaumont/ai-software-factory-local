#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tck_tag="1.0.0.alpha2"
tck_commit="29063fe95e903cddac5d8ff811ab94df1ad6ef86"
tck_fix_commit="8318fd843d4b69e3cb816b59192c8fd3f241c947"
tck_image="ai-factory/a2a-tck:${tck_tag}-${tck_commit:0:12}"
network="ai-factory-a2a-tck-$$-${RANDOM}"
checkout="$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-tck.XXXXXX")"
container="ai-factory-a2a-tck-$$-${RANDOM}"
sut="a2a-tck-sut-$$-${RANDOM}"
cleanup() {
  docker rm -f "${container}" >/dev/null 2>&1 || true
  docker rm -f "${sut}" >/dev/null 2>&1 || true
  docker network rm "${network}" >/dev/null 2>&1 || true
  rm -rf "${checkout}"
}
trap cleanup EXIT

git clone --quiet --depth 1 --branch "${tck_tag}" https://github.com/a2aproject/a2a-tck.git "${checkout}"
actual="$(git -C "${checkout}" rev-parse HEAD)"
if [[ "${actual}" != "${tck_commit}" ]]; then
  echo "TCK commit mismatch: expected ${tck_commit}, got ${actual}" >&2
  exit 1
fi
git -C "${checkout}" apply "${root}/infrastructure/a2a/tck/upstream-213.patch"

mkdir -p "${root}/docs/evidence/a2a/tck"
docker build --quiet \
  --file "${root}/infrastructure/a2a/tck/Dockerfile" \
  --tag "${tck_image}" \
  "${checkout}" \
  >/dev/null
docker network create --internal "${network}" >/dev/null
docker run --detach --name "${sut}" \
  --network "${network}" \
  --network-alias a2a-tck-sut \
  --read-only \
  --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  -e AI_FACTORY_AGENT_ROLE=developer \
  -e AI_FACTORY_AGENT_ENDPOINT=http://a2a-tck-sut:8090/a2a \
  -e AI_FACTORY_A2A_TCK_ENABLED=true \
  -e AI_FACTORY_A2A_CARD_JWK_SET_PATH=/run/secrets/a2a-card-jwks \
  -e AI_FACTORY_A2A_CARD_ACTIVE_KID=a2a-developer-local-v1 \
  -v "${root}/.local/a2a-secrets/roles/developer/card-jwks.json:/run/secrets/a2a-card-jwks:ro" \
  ai-factory-a2a-agent-runtime:0.1.0 \
  >/dev/null
for attempt in $(seq 1 30); do
  status="$(docker inspect --format '{{.State.Health.Status}}' "${sut}")"
  [[ "${status}" == "healthy" ]] && break
  [[ "${status}" == "unhealthy" ]] && docker logs "${sut}" && exit 1
  sleep 1
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "${sut}")" == "healthy" ]] || {
  docker logs "${sut}"
  echo "A2A TCK SUT did not become healthy" >&2
  exit 1
}
docker create --name "${container}" \
  --network "${network}" \
  -e PYTHONPATH=/harness \
  -v "${root}/infrastructure/a2a/tck:/harness:ro" \
  -v "${root}/docs/evidence/a2a/tck:/tck/reports" \
  "${tck_image}" \
  /tck/.venv/bin/python /harness/run_with_identity.py "$@" \
  >/dev/null
docker start --attach "${container}"
