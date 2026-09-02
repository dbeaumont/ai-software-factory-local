package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityDecisionValidatorTest {
    private static final String POLICY_URI = "evidence://task-1/policy";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecurityDecisionValidator validator = new SecurityDecisionValidator();

    @Test
    void preservesTheExactNormalizedFindings() throws Exception {
        JsonNode supplied = rejectedFindings();
        ObjectNode assessment = assessmentWith(supplied);
        ((ObjectNode) assessment.path("normalized_findings").path("findings").get(0))
                .put("severity", "LOW");

        assertThatThrownBy(() -> validator.validate(assessment, supplied, Set.of()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("were altered");
    }

    @Test
    void rejectsAcceptedThreatAndFindingDowngradeWithoutSuppliedPolicy() throws Exception {
        JsonNode supplied = rejectedFindings();
        ObjectNode assessment = assessmentWith(supplied);
        ((ArrayNode) assessment.path("threats")).add(mapper.readTree("""
                {"threat_id":"threat-1","asset":"tokens","scenario":"token disclosure",
                 "severity":"HIGH","mitigation":"rotate tokens","status":"ACCEPTED"}
                """));
        assertThatThrownBy(() -> validator.validate(assessment, supplied, Set.of()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("accepted threat");

        addDowngrade(assessment);
        assertThatThrownBy(() -> validator.validate(assessment, supplied, Set.of()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not explicitly supplied");
    }

    @Test
    void acceptsDowngradeOnlyWhenTheWorkflowSuppliesTheExactTraceableDecision() throws Exception {
        JsonNode supplied = rejectedFindings();
        ObjectNode assessment = assessmentWith(supplied);
        addDowngrade(assessment);
        ((ArrayNode) assessment.path("evidence")).add(mapper.readTree("""
                {"uri":"evidence://task-1/policy",
                 "digest":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                 "type":"POLICY_DECISION"}
                """));
        SecurityDecisionValidator.PolicyDecision decision = new SecurityDecisionValidator.PolicyDecision(
                "FINDING", "CVE-1", "DOWNGRADE", "HIGH", "MEDIUM", POLICY_URI,
                "d".repeat(64), "temporary compensating control");

        assertThat(validator.validate(assessment, supplied, Set.of(decision))).isSameAs(assessment);
    }

    @Test
    void refusesPassedWhenARejectedFindingHasNoExplicitDecision() throws Exception {
        JsonNode supplied = rejectedFindings();
        ObjectNode assessment = assessmentWith(supplied);

        assertThatThrownBy(() -> validator.validate(assessment, supplied, Set.of()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("without an explicit policy");
    }

    private ObjectNode assessmentWith(JsonNode findings) throws Exception {
        ObjectNode assessment = (ObjectNode) fixture().deepCopy();
        assessment.set("normalized_findings", findings.deepCopy());
        return assessment;
    }

    private void addDowngrade(ObjectNode assessment) throws Exception {
        ((ArrayNode) assessment.path("risk_decisions")).add(mapper.readTree("""
                {"target_type":"FINDING","target_id":"CVE-1","action":"DOWNGRADE",
                 "original_severity":"HIGH","effective_severity":"MEDIUM",
                 "policy_decision_uri":"evidence://task-1/policy",
                 "policy_decision_digest":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                 "rationale":"temporary compensating control"}
                """));
    }

    private JsonNode rejectedFindings() throws Exception {
        return mapper.readTree("""
                {"schema_version":"1","task_id":"task-1","attempt_id":"attempt-1",
                 "source_commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","scanner":"trivy",
                 "verdict":"REJECTED","findings":[{"id":"CVE-1","severity":"HIGH",
                 "component":"library","file":null,"rule":"known-vulnerability","proof":"scanner match",
                 "recommendation":"upgrade"}],
                 "summary":{"unknown":0,"low":0,"medium":0,"high":1,"critical":0},
                 "evidence":{"uri":"evidence://task-1/security",
                 "digest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "status":"COMPLETE"}}
                """);
    }

    private JsonNode fixture() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        return mapper.readTree(Files.readString(fixture)).path("documents").path("security-assessment-v1");
    }
}
