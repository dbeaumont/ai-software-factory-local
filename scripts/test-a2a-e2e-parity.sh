#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
ruby scripts/verify-a2a-functional-baseline.rb
ruby scripts/verify-a2a-e2e-parity.rb

tests=EvaluationSuiteCoverageTest,ShortCodePathPlannerTest,HierarchicalPathPlannerTest,WorkflowRoutingServiceTest,PipelineCompatibilityTest,DelegationSchedulerTest,TemporalFailureModesTest,TemporalFailureClassifierTest,TemporalCascadeCancellationTest,SoftwareFactoryWorkflowTest,MultiAgentContractValidatorTest,A2aBusinessContractGuardTest,A2aDelegationWorkflowTest,A2aOutputArtifactMappingTest,A2aInputSkillMappingTest,A2aTemporalStateMapperTest
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml -f apps/orchestrator/pom.xml test -Dtest="$tests"

echo "A2A business E2E parity campaign passed."
