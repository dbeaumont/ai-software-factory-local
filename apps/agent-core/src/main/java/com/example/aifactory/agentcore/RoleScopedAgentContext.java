package com.example.aifactory.agentcore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Immutable capability boundary for one agent process.
 *
 * <p>The catalog, prompt repository and global contract registry are used only while constructing this object and
 * are deliberately not exposed. Runtime code can therefore see one identity, one prompt and only the contracts
 * and tools granted to that identity.</p>
 */
public final class RoleScopedAgentContext {
    private final AgentManifest identity;
    private final String systemPrompt;
    private final String promptFingerprint;
    private final String compatibilitySystemPrompt;
    private final String compatibilityPromptFingerprint;
    private final AgentContractValidator contracts;
    private final Set<String> inputContracts;
    private final Set<String> outputContracts;

    private RoleScopedAgentContext(AgentManifest identity, String systemPrompt, String promptFingerprint,
                                   String compatibilitySystemPrompt, String compatibilityPromptFingerprint,
                                   AgentContractValidator contracts) {
        this.identity = identity;
        this.systemPrompt = systemPrompt;
        this.promptFingerprint = promptFingerprint;
        this.compatibilitySystemPrompt = compatibilitySystemPrompt;
        this.compatibilityPromptFingerprint = compatibilityPromptFingerprint;
        this.contracts = contracts;
        this.inputContracts = Set.copyOf(identity.inputContracts());
        this.outputContracts = Set.copyOf(identity.outputContracts());
    }

    public static RoleScopedAgentContext load(String role, ObjectMapper mapper) {
        AgentCatalog catalog = new AgentCatalog();
        PromptRepository prompts = new PromptRepository();
        AgentManifest identity = AgentManifest.load(role, catalog, prompts);
        String compatibility = identity.compatibilityPromptName();
        return new RoleScopedAgentContext(identity, prompts.load(identity.promptName()),
                prompts.fingerprint(identity.promptName()),
                compatibility == null ? null : prompts.load(compatibility),
                compatibility == null ? null : prompts.fingerprint(compatibility),
                new AgentContractValidator(mapper, catalog));
    }

    public AgentManifest identity() { return identity; }
    public String systemPrompt() { return systemPrompt; }
    public String promptFingerprint() { return promptFingerprint; }
    public String systemPrompt(String inputContract) {
        if (!"pipeline-agent-task-v1".equals(inputContract)) return systemPrompt;
        if (compatibilitySystemPrompt == null) {
            throw new SecurityException("Pipeline compatibility is not granted to role " + identity.role());
        }
        return compatibilitySystemPrompt;
    }
    public String promptFingerprint(String inputContract) {
        if (!"pipeline-agent-task-v1".equals(inputContract)) return promptFingerprint;
        if (compatibilityPromptFingerprint == null) {
            throw new SecurityException("Pipeline compatibility is not granted to role " + identity.role());
        }
        return compatibilityPromptFingerprint;
    }
    public Set<String> acceptedInputContracts() { return inputContracts; }
    public Set<String> producedOutputContracts() { return outputContracts; }
    public Set<String> allowedTools() { return identity.allowedTools(); }
    public Set<String> allowedDelegations() { return identity.mayDelegateTo(); }

    public void requireActiveRole(String role) {
        if (!identity.role().equals(role)) {
            throw new SecurityException("A second agent role cannot be activated in this runtime");
        }
    }

    public void requireTool(String tool) {
        if (!identity.allowedTools().contains(tool)) {
            throw new SecurityException("Tool is not granted to role " + identity.role());
        }
    }

    public void requireDelegation(String targetRole) {
        if (!identity.mayDelegateTo().contains(targetRole)) {
            throw new SecurityException("Delegation is not granted from " + identity.role() + " to " + targetRole);
        }
    }

    public JsonNode validateInput(String contract, JsonNode document, AgentContractValidator.Context context) {
        requireContract(inputContracts, contract, "input");
        return contracts.validate(contract, document, context);
    }

    public JsonNode validateOutput(String contract, JsonNode document, AgentContractValidator.Context context) {
        requireContract(outputContracts, contract, "output");
        return contracts.validate(contract, document, context);
    }

    public JsonNode validateOutput(String contract, String document, AgentContractValidator.Context context) {
        requireContract(outputContracts, contract, "output");
        return contracts.validate(contract, document, context);
    }

    private void requireContract(Set<String> granted, String contract, String direction) {
        if (!granted.contains(contract)) {
            throw new SecurityException(direction + " contract is not granted to role " + identity.role());
        }
    }
}
