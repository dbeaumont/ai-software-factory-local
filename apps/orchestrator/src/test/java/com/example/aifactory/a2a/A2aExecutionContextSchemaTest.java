package com.example.aifactory.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class A2aExecutionContextSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Schema schema = loadSchema();

    @Test
    void acceptsOnlyTheBoundedCorrelationFields() throws Exception {
        JsonNode valid = objectMapper.readTree("""
                {
                  "schemaVersion": "1",
                  "taskId": "task-1",
                  "attemptId": "attempt-1",
                  "workflowId": "ai-factory/task-1/attempt-1",
                  "workflowRunId": "4c78345b-31f3-4f68-8c6a-2d8d796952a1",
                  "repositoryId": "customer-api",
                  "sourceCommit": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "delegationId": "delegation-1",
                  "parentDelegationId": null,
                  "agentRole": "supervisor",
                  "inputDigests": ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
                }
                """);

        assertThat(schema.validate(valid)).isEmpty();

        var withSecret = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withSecret).put("accessToken", "secret");
        assertThat(schema.validate(withSecret)).isNotEmpty();

        var unknownRole = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) unknownRole).put("agentRole", "workflow");
        assertThat(schema.validate(unknownRole)).isNotEmpty();

        var malformedDigest = valid.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) malformedDigest.path("inputDigests"))
                .set(0, objectMapper.getNodeFactory().textNode("not-a-digest"));
        assertThat(schema.validate(malformedDigest)).isNotEmpty();
    }

    @Test
    void extensionIdentifierAndSchemaLocationAreStable() {
        assertThat(A2aExtensions.EXECUTION_CONTEXT_V1)
                .isEqualTo("https://ai-factory.local/extensions/execution-context/v1");
        assertThat(A2aExtensions.EXECUTION_CONTEXT_SCHEMA_V1)
                .isEqualTo("a2a/extensions/execution-context-v1.schema.json");
    }

    private Schema loadSchema() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(A2aExtensions.EXECUTION_CONTEXT_SCHEMA_V1)) {
            if (input == null) {
                throw new IllegalStateException("Missing A2A execution context schema");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load A2A execution context schema", exception);
        }
    }
}
