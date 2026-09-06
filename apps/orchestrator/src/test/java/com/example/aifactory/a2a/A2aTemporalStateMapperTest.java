package com.example.aifactory.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aTemporalStateMapperTest {
    private static final Path DOCUMENTATION = Path.of("../..", "resources", "a2a", "temporal-state-mapping-v1.json");
    private final A2aTemporalStateMapper mapper = new A2aTemporalStateMapper();

    @Test
    void mapsEverySupportedStateAndKeepsBusinessRejectionDistinctFromFailure() throws IOException {
        Set<A2aContracts.TaskState> supported = EnumSet.complementOf(EnumSet.of(A2aContracts.TaskState.UNKNOWN));
        assertThat(supported).allSatisfy(state -> assertThat(mapper.map(state)).isNotNull());

        assertThat(mapper.map(A2aContracts.TaskState.REJECTED).failureKind())
                .isEqualTo(A2aTemporalStateMapper.FailureKind.BUSINESS);
        assertThat(mapper.map(A2aContracts.TaskState.REJECTED).workflowAction())
                .isEqualTo("BUSINESS_REJECTION_NO_RETRY");
        assertThat(mapper.map(A2aContracts.TaskState.FAILED).failureKind())
                .isEqualTo(A2aTemporalStateMapper.FailureKind.UNCLASSIFIED);
        assertThat(mapper.map(A2aContracts.TaskState.FAILED).workflowAction())
                .isEqualTo("CLASSIFY_BEFORE_RETRY");
        assertThatThrownBy(() -> mapper.map(A2aContracts.TaskState.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executableMappingMatchesVersionedDocumentation() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(DOCUMENTATION));
        assertThat(root.path("schemaVersion").asText()).isEqualTo("1.0.0");
        JsonNode mappings = root.path("mappings");
        assertThat(mappings).hasSize(8);

        Set<String> documentedStates = new java.util.HashSet<>();
        mappings.forEach(mapping -> {
            String protocolState = mapping.path("a2aState").asText();
            documentedStates.add(protocolState.replace("TASK_STATE_", ""));
            A2aContracts.TaskState state = A2aContracts.TaskState.valueOf(protocolState.replace("TASK_STATE_", ""));
            A2aTemporalStateMapper.TemporalTransition transition = mapper.map(state);
            assertThat(mapping.path("eventType").asText()).isEqualTo(transition.eventType());
            assertThat(mapping.path("workflowAction").asText()).isEqualTo(transition.workflowAction());
            assertThat(mapping.path("terminal").asBoolean()).isEqualTo(transition.terminal());
            assertThat(mapping.path("failureKind").asText()).isEqualTo(transition.failureKind().name());
        });
        assertThat(documentedStates).isEqualTo(EnumSet.complementOf(EnumSet.of(A2aContracts.TaskState.UNKNOWN))
                .stream().map(Enum::name).collect(Collectors.toSet()));
    }
}
