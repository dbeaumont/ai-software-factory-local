package com.example.aifactory.workflow.projection;

import io.grpc.Status;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporalProjectionHistorySourceTest {
    @Test
    void reportsAnExpiredHistoryWithoutProducingPartialFacts() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.fetchHistory("workflow-expired", "run-expired"))
                .thenThrow(Status.NOT_FOUND.withDescription("history expired").asRuntimeException());
        TemporalProjectionHistorySource source = new TemporalProjectionHistorySource(
                client, mock(DataConverter.class));

        assertThatThrownBy(() -> source.read("workflow-expired", "run-expired"))
                .isInstanceOf(ProjectionHistoryUnavailableException.class)
                .hasMessageContaining("unavailable or expired")
                .hasMessageContaining("workflow-expired")
                .hasMessageContaining("run-expired");
    }
}
