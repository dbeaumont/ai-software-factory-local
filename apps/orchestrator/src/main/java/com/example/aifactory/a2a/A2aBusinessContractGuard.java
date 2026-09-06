package com.example.aifactory.a2a;

import com.example.aifactory.service.MultiAgentContractValidator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Business-schema validation boundary deliberately separate from A2A model/transport validation. */
@Component
public final class A2aBusinessContractGuard {
    private final A2aContractMapping mapping;
    private final MultiAgentContractValidator contracts;

    public A2aBusinessContractGuard(A2aContractMapping mapping, MultiAgentContractValidator contracts) {
        this.mapping = mapping;
        this.contracts = contracts;
    }

    public JsonNode beforeSend(String role, String contract, JsonNode document,
                               MultiAgentContractValidator.ContractContext context) {
        mapping.requireInput(role, contract);
        return contracts.validate(contract, document, context);
    }

    public JsonNode afterReceive(String role, String contract, JsonNode document,
                                 MultiAgentContractValidator.ContractContext context) {
        mapping.requirePrimaryOutput(role, contract);
        return contracts.validate(contract, document, context);
    }
}
