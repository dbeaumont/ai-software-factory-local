package com.example.aifactory.a2a;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class A2aEnvelopeSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Schema schema = loadSchema();

    @Test
    void acceptsTheVersionedBoundedEnvelopeFixture() throws Exception {
        assertThat(schema.validate(load("a2a/fixtures/a2a-envelope-v1.json"))).isEmpty();
    }

    @Test
    void rejectsControlPlaneRoleUnboundedBudgetExternalReferenceAndUnknownField() throws Exception {
        JsonNode fixture = load("a2a/fixtures/a2a-envelope-v1.json");
        ObjectNode role = (ObjectNode) fixture.deepCopy();
        role.put("target_role", "workflow");
        assertThat(schema.validate(role)).isNotEmpty();

        ObjectNode budget = (ObjectNode) fixture.deepCopy();
        ((ObjectNode) budget.path("budget")).put("max_turns", 1000);
        assertThat(schema.validate(budget)).isNotEmpty();

        ObjectNode reference = (ObjectNode) fixture.deepCopy();
        ((ObjectNode) reference.path("input_references").path(0)).put("uri", "https://attacker.invalid/input");
        assertThat(schema.validate(reference)).isNotEmpty();

        ObjectNode extension = (ObjectNode) fixture.deepCopy();
        extension.put("secret", "must-not-cross-a2a");
        assertThat(schema.validate(extension)).isNotEmpty();
    }

    private Schema loadSchema() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("a2a/schemas/a2a-envelope-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing A2A envelope schema");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load A2A envelope schema", exception);
        }
    }

    private JsonNode load(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return mapper.readTree(input);
        }
    }
}
