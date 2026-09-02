package com.example.aifactory.workflow;

import com.example.aifactory.service.McpToolInvoker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepositoryTest {
    @Test
    void keepsTheWorkflowPortIndependentFromMcpClientTypes() {
        assertThat(Arrays.stream(EvidenceRepository.class.getDeclaredMethods())
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(method.getReturnType()),
                        Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type == McpToolInvoker.class)).isTrue();
    }

    @Test
    void protectsBinaryEvidenceFromCallerMutation() {
        byte[] content = {1, 2, 3};
        EvidenceRepository.StoreRequest request = new EvidenceRepository.StoreRequest(
                "task-1", "attempt-1", "tests", "text/plain", content, "a".repeat(64), "workflow");
        content[0] = 9;
        byte[] returned = request.content();
        returned[1] = 9;

        assertThat(request.content()).containsExactly(1, 2, 3);
    }
}
