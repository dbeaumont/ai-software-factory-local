package com.example.aifactory.service;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates every inter-agent document against the host-pinned local contract catalog. */
@Component
public final class MultiAgentContractValidator {
    private static final String ROOT = "multiagents/schemas/";
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final Set<String> KNOWN_ROLES = Set.of("workflow", "supervisor", "architecture-agent",
            "impact-analysis", "dependencies-contracts", "code-agent", "developer", "patch-repair",
            "test-agent", "test-design", "test-evidence", "security-agent", "threat-model",
            "security-findings", "independent-reviewer");
    private static final Map<String, Set<String>> REFERENCE_FIELDS = Map.ofEntries(
            Map.entry("specialist-task-v1", Set.of("delegation_plan_id", "node_id")),
            Map.entry("specialist-result-v1", Set.of("specialist_task_id", "delegation_plan_id", "node_id")),
            Map.entry("agent-run-event-v1", Set.of("specialist_task_id", "node_id")),
            Map.entry("supervisor-decision-v1", Set.of("delegation_plan_id", "replacement_plan_id", "human_decision_request_id")),
            Map.entry("architecture-assessment-v1", Set.of("specialist_task_id")),
            Map.entry("integration-proposal-v1", Set.of("delegation_plan_id", "node_id", "architecture_assessment_id")),
            Map.entry("code-task-v1", Set.of("delegation_plan_id", "node_id", "architecture_assessment_id")),
            Map.entry("patch-proposal-v1", Set.of("code_task_id", "node_id")),
            Map.entry("test-assessment-v1", Set.of("strategy_id")));
    private final ObjectMapper objectMapper;
    private final Map<String, Schema> schemas;

    public MultiAgentContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String vulnerabilitySchema = readTextResource("mcp/schemas/vulnerability-result-v1.schema.json");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(
                        "https://ai-factory.local/mcp/schemas/vulnerability-result-v1.schema.json",
                        vulnerabilitySchema)));
        Map<String, String> catalog = readCatalog();
        Map<String, Schema> loaded = new LinkedHashMap<>();
        catalog.forEach((name, resource) -> loaded.put(name, load(registry, resource)));
        this.schemas = Map.copyOf(loaded);
    }

    public Set<String> contracts() {
        return schemas.keySet();
    }

    public JsonNode validate(String contract, String document) {
        try {
            if (document == null || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new ContractValidationException(contract, "document exceeds maximum size");
            }
            return validate(contract, objectMapper.readTree(document));
        } catch (ContractValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ContractValidationException(contract, "document is not valid JSON", exception);
        }
    }

    public JsonNode validate(String contract, JsonNode document) {
        Schema schema = schemas.get(contract);
        if (schema == null) throw new ContractValidationException(contract, "unknown contract");
        List<Error> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            throw new ContractValidationException(contract,
                    "document violates the local schema (" + errors.size() + " error(s), first keyword: "
                            + errors.getFirst().getKeyword() + ")");
        }
        return document;
    }

    public JsonNode validate(String contract, JsonNode document, ContractContext context) {
        JsonNode validated = validate(contract, document);
        requireBinding(contract, "task_id", validated.path("task_id"), context.taskId());
        requireBinding(contract, "attempt_id", validated.path("attempt_id"), context.attemptId());
        for (String roleField : List.of("role", "parent_role", "root_role")) {
            JsonNode role = validated.path(roleField);
            if (role.isTextual() && !KNOWN_ROLES.contains(role.asText())) {
                throw new ContractValidationException(contract, "unknown role in " + roleField);
            }
        }
        for (String collection : List.of("sources", "input_results")) {
            for (JsonNode entry : validated.path(collection)) {
                JsonNode role = entry.path("role");
                if (role.isTextual() && !KNOWN_ROLES.contains(role.asText())) {
                    throw new ContractValidationException(contract, "unknown role in " + collection);
                }
            }
        }
        for (String field : REFERENCE_FIELDS.getOrDefault(contract, Set.of())) {
            JsonNode reference = validated.path(field);
            if (reference.isTextual() && !context.allowedReferenceIds().contains(reference.asText())) {
                throw new ContractValidationException(contract, "reference outside task in " + field);
            }
        }
        if ("delegation-plan-v1".equals(contract)) {
            for (JsonNode citation : validated.path("citations")) {
                String referenceId = citation.path("reference_id").asText();
                if (!context.allowedReferenceIds().contains(referenceId)) {
                    throw new ContractValidationException(contract, "citation outside task: " + referenceId);
                }
            }
        }
        return validated;
    }

    public JsonNode validate(String contract, String document, ContractContext context) {
        JsonNode parsed = validate(contract, document);
        return validate(contract, parsed, context);
    }

    private static void requireBinding(String contract, String field, JsonNode actual, String expected) {
        if (!actual.isTextual() || !expected.equals(actual.asText())) {
            throw new ContractValidationException(contract, field + " is outside the current task context");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readCatalog() {
        try (InputStream input = resource("contract-catalog-v1.json")) {
            JsonNode root = objectMapper.readTree(input);
            if (!"1".equals(root.path("catalog_version").asText())) {
                throw new IllegalStateException("Unsupported multi-agent contract catalog version");
            }
            return objectMapper.convertValue(root.path("contracts"), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load multi-agent contract catalog", exception);
        }
    }

    private Schema load(SchemaRegistry registry, String resource) {
        try (InputStream input = resource(resource)) {
            return registry.getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load multi-agent schema: " + resource, exception);
        }
    }

    private InputStream resource(String name) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + name);
        if (input == null) throw new IllegalStateException("Missing multi-agent contract resource: " + name);
        return input;
    }

    private String readTextResource(String path) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing contract dependency: " + path);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read contract dependency: " + path, exception);
        }
    }

    public static final class ContractValidationException extends IllegalArgumentException {
        private final String contract;

        ContractValidationException(String contract, String message) {
            super(message);
            this.contract = contract;
        }

        ContractValidationException(String contract, String message, Throwable cause) {
            super(message, cause);
            this.contract = contract;
        }

        public String contract() {
            return contract;
        }
    }

    public record ContractContext(String taskId, String attemptId, Set<String> allowedReferenceIds) {
        public ContractContext {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()) {
                throw new IllegalArgumentException("Task and attempt identifiers are required");
            }
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
