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
    private final ObjectMapper objectMapper;
    private final Map<String, Schema> schemas;

    public MultiAgentContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
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
}
