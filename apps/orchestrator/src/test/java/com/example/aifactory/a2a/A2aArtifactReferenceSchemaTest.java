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

class A2aArtifactReferenceSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Schema schema = schema();

    @Test
    void acceptsACompleteImmutableEvidenceReference() throws Exception {
        assertThat(schema.validate(fixture())).isEmpty();
    }

    @Test
    void rejectsExternalUriMalformedDigestOversizeAndUnknownClassification() throws Exception {
        JsonNode fixture = fixture();
        ObjectNode external = (ObjectNode) fixture.deepCopy();
        external.put("uri", "https://external.invalid/proof");
        assertThat(schema.validate(external)).isNotEmpty();
        ObjectNode digest = (ObjectNode) fixture.deepCopy();
        digest.put("digest", "bad");
        assertThat(schema.validate(digest)).isNotEmpty();
        ObjectNode size = (ObjectNode) fixture.deepCopy();
        size.put("size_bytes", 10_485_761);
        assertThat(schema.validate(size)).isNotEmpty();
        ObjectNode classification = (ObjectNode) fixture.deepCopy();
        classification.put("classification", "SECRET-UNBOUNDED");
        assertThat(schema.validate(classification)).isNotEmpty();
    }

    private Schema schema() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("a2a/schemas/a2a-artifact-reference-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing artifact reference schema");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private JsonNode fixture() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("a2a/fixtures/a2a-artifact-reference-v1.json")) {
            if (input == null) throw new IllegalStateException("Missing artifact reference fixture");
            return mapper.readTree(input);
        }
    }
}
