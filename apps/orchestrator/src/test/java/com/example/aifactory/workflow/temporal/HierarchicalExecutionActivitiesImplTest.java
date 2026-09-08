package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskRoutingFacts;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.MultiAgentContractValidator;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalExecutionActivitiesImplTest {
    @Test
    void persistsAValidatedAndAttemptBoundSpecialistTask() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea/customer-api.git", "main", "Assess the cross-module change",
                LlmMode.CLOUD, TaskRoutingFacts.qualifiedLowRiskFixture()));
        state.sourceCommit = "a".repeat(40);
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        when(evidence.store(any())).thenAnswer(invocation -> {
            EvidenceRepository.StoreRequest stored = invocation.getArgument(0);
            return new EvidenceRepository.StoredEvidence(
                    "evidence://task-1/attempt-1/specialist-task/architecture", stored.digest(),
                    "COMPLETE", "application/json", stored.content().length, "INTERNAL",
                    Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"));
        });
        var activities = new HierarchicalExecutionActivitiesImpl(memory, evidence,
                new MultiAgentContractValidator(mapper), mapper,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var part = activities.prepareSpecialistTask(new HierarchicalExecutionActivities.PrepareSpecialistTask(
                "task-1", "attempt-1", "customer-api", "a".repeat(40), "plan-1", "architecture",
                "supervisor", "architecture-agent", List.of(), Set.of("."), Set.of(),
                Set.of("context.list_tree", "context.search_code"),
                new DelegationWorkflow.Budget(10_000, 10_000_000, 6, 600),
                List.of("Return a source-bound architecture assessment")));

        assertThat(part.data()).containsEntry("contract", "specialist-task-v1")
                .containsEntry("digest", part.data().get("digest"));
        ArgumentCaptor<EvidenceRepository.StoreRequest> stored =
                ArgumentCaptor.forClass(EvidenceRepository.StoreRequest.class);
        verify(evidence).store(stored.capture());
        var document = mapper.readTree(stored.getValue().content());
        assertThat(document.path("role").asText()).isEqualTo("architecture-agent");
        assertThat(document.path("objective").asText()).isEqualTo("Assess the cross-module change");
        assertThat(document.path("deadline").asText()).isEqualTo("2026-09-08T00:10:00Z");
        assertThat(document.path("scope").path("read_paths").get(0).asText()).isEqualTo(".");
        assertThat(stored.getValue().digest()).isEqualTo(part.data().get("digest"));
    }
}
