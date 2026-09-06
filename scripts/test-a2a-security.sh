#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

settings=${MAVEN_SETTINGS_FILE:-apps/orchestrator/.mvn/settings-direct.xml}
runtime_tests=A2aSendMessageServiceTest,A2aW3cTraceContextTest,A2aSecurityDeclarationTest,A2aAuthorizationOrderingTest,A2aGetTaskTest,A2aCancelTaskTest,A2aListTasksTest
client_tests=A2aAgentCardVerifierTest,A2aUrlPolicyTest,AllowListedAgentRegistryTest,A2aPayloadLimitsTest,A2aMessageIdempotencyTest,A2aJsonRpcHttpTransportTest,A2aExecutionContextSchemaTest,CachingAgentCardResolverTest

mvn -B -s "$settings" -pl apps/a2a-agent-runtime -am test \
  -Dtest="$runtime_tests" -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -s "$settings" -pl apps/orchestrator -am test \
  -Dtest="$client_tests" -Dsurefire.failIfNoSpecifiedTests=false

echo "A2A adversarial security campaign passed: runtime and orchestrator boundaries reject all corpus cases."
