package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityFindingsInputValidatorTest {
    private final SecurityFindingsInputValidator validator =
            new SecurityFindingsInputValidator(new ObjectMapper());

    @Test
    void acceptsNormalizedFindingsOnlyWithTheExactEvidenceProvidedByWorkflow() {
        SecurityFindingsInputValidator.Context context = new SecurityFindingsInputValidator.Context(
                "task-1", "attempt-1", "a".repeat(40), Set.of(
                new SecurityFindingsInputValidator.EvidenceReference(
                        "evidence://task-1/security", "b".repeat(64))));

        assertThat(validator.validate(findings("b".repeat(64)), context).path("scanner").asText())
                .isEqualTo("trivy");
        assertThatThrownBy(() -> validator.validate(findings("c".repeat(64)), context))
                .isInstanceOf(SecurityException.class).hasMessageContaining("supplied evidence");
    }

    private static String findings(String digest) {
        return """
                {"schema_version":"1","task_id":"task-1","attempt_id":"attempt-1",
                 "source_commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","scanner":"trivy",
                 "verdict":"REJECTED","findings":[{"id":"CVE-1","severity":"HIGH",
                 "component":"library","file":null,"rule":"known-vulnerability","proof":"scanner match",
                 "recommendation":"upgrade"}],
                 "summary":{"unknown":0,"low":0,"medium":0,"high":1,"critical":0},
                 "evidence":{"uri":"evidence://task-1/security","digest":"%s","status":"COMPLETE"}}
                """.formatted(digest);
    }
}
