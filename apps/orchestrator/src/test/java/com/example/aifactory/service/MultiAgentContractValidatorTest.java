package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiAgentContractValidatorTest {
    private final MultiAgentContractValidator validator = new MultiAgentContractValidator(new ObjectMapper());

    @Test
    void loadsEveryPinnedContractAndRejectsAnInvalidDocument() {
        assertThat(validator.contracts()).hasSize(15);
        validator.contracts().forEach(contract -> assertThatThrownBy(() -> validator.validate(contract, "{}"))
                .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class)
                .hasMessageContaining("violates the local schema"));
    }

    @Test
    void rejectsUnknownContractAndMalformedJson() {
        assertThatThrownBy(() -> validator.validate("unknown-v1", "{}"))
                .hasMessageContaining("unknown contract");
        assertThatThrownBy(() -> validator.validate("delegation-plan-v1", "{"))
                .hasMessageContaining("not valid JSON");
    }
}
