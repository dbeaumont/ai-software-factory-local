package com.example.aifactory.agentcore;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates bounded agent documents independently of A2A and Temporal envelopes. */
public final class AgentContractValidator {
    private static final String ROOT = "multiagents/schemas/";
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private final ObjectMapper mapper;
    private final Map<String, Schema> schemas;
    private final Set<String> roles;

    public AgentContractValidator(ObjectMapper mapper, AgentCatalog catalog) {
        this.mapper = mapper;
        this.roles = catalog.roles().keySet();
        String vulnerability = text("mcp/schemas/vulnerability-result-v1.schema.json");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(
                        "https://ai-factory.local/mcp/schemas/vulnerability-result-v1.schema.json", vulnerability)));
        Map<String, Schema> loaded = new LinkedHashMap<>();
        catalog().forEach((name, resource) -> loaded.put(name, schema(registry, resource)));
        schemas = Map.copyOf(loaded);
    }

    public Set<String> contracts() { return schemas.keySet(); }

    public JsonNode validate(String contract, String document, Context context) {
        try {
            if (document == null || document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new ContractValidationException(contract, "document exceeds maximum size");
            }
            return validate(contract, mapper.readTree(document), context);
        } catch (ContractValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ContractValidationException(contract, "document is not valid JSON", exception);
        }
    }

    public JsonNode validate(String contract, JsonNode document, Context context) {
        Schema schema = schemas.get(contract);
        if (schema == null) throw new ContractValidationException(contract, "unknown contract");
        List<Error> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            Error error = errors.getFirst();
            throw new ContractValidationException(contract, "schema violation: " + error.getKeyword()
                    + " at " + error.getInstanceLocation());
        }
        require(document, "task_id", context.taskId(), contract);
        require(document, "attempt_id", context.attemptId(), contract);
        for (String field : List.of("role", "parent_role", "root_role")) {
            JsonNode role = document.path(field);
            if (role.isTextual() && !roles.contains(role.asText())) {
                throw new ContractValidationException(contract, "unknown role in " + field);
            }
        }
        return document;
    }

    private static void require(JsonNode document, String field, String expected, String contract) {
        if (!document.path(field).isTextual() || !expected.equals(document.path(field).asText())) {
            throw new ContractValidationException(contract, field + " is outside the task context");
        }
    }
    @SuppressWarnings("unchecked")
    private Map<String, String> catalog() {
        try (InputStream input = resource("contract-catalog-v1.json")) {
            JsonNode root = mapper.readTree(input);
            if (!"1".equals(root.path("catalog_version").asText())) {
                throw new IllegalStateException("Unsupported contract catalog");
            }
            return mapper.convertValue(root.path("contracts"), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load contract catalog", exception);
        }
    }
    private Schema schema(SchemaRegistry registry, String name) {
        try (InputStream input = resource(name)) {
            return registry.getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load schema " + name, exception);
        }
    }
    private InputStream resource(String name) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + name);
        if (input == null) throw new IllegalStateException("Missing contract " + name);
        return input;
    }
    private String text(String resource) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read " + resource, exception);
        }
    }

    public record Context(String taskId, String attemptId, Set<String> allowedReferenceIds) {
        public Context {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()) {
                throw new IllegalArgumentException("Task and attempt are required");
            }
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }
    public static final class ContractValidationException extends IllegalArgumentException {
        private final String contract;
        ContractValidationException(String contract, String message) { super(message); this.contract = contract; }
        ContractValidationException(String contract, String message, Throwable cause) {
            super(message, cause); this.contract = contract;
        }
        public String contract() { return contract; }
    }
}
