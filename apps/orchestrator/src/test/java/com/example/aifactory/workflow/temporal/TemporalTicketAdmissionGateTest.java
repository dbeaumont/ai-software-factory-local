package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.config.KillSwitchProperties;
import com.example.aifactory.service.OperationalKillSwitch;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalTicketAdmissionGateTest {
    @TempDir
    Path tempDirectory;

    @Test
    void probesNamespaceOffTheReactorNonBlockingThread() {
        Fixture fixture = fixture();
        when(fixture.factory.isStarted()).thenReturn(true);
        when(fixture.factory.isShutdown()).thenReturn(false);
        AtomicBoolean usedNonBlockingThread = new AtomicBoolean(true);
        when(fixture.stub.describeNamespace(any())).thenAnswer(invocation -> {
            usedNonBlockingThread.set(Schedulers.isInNonBlockingThread());
            return null;
        });

        fixture.gate.verifyActive().block(Duration.ofSeconds(2));

        assertThat(usedNonBlockingThread).isFalse();
    }

    @Test
    void refusesBeforeNetworkWhenWorkersAreStopped() {
        Fixture fixture = fixture();
        when(fixture.factory.isStarted()).thenReturn(false);

        assertThatThrownBy(() -> fixture.gate.verifyActive().block(Duration.ofSeconds(2)))
                .isInstanceOf(TemporalAdmissionUnavailableException.class);
        verify(fixture.stub, never()).describeNamespace(any());
    }

    @Test
    void refusesBeforeNetworkWhenTheGlobalKillSwitchIsActive() throws Exception {
        Path control = tempDirectory.resolve("kill-switch.properties");
        Files.writeString(control, "revision=incident-1\nglobal.disabled=true\n");
        Fixture fixture = fixture(new OperationalKillSwitch(new KillSwitchProperties(control.toString())));
        when(fixture.factory.isStarted()).thenReturn(true);

        assertThatThrownBy(() -> fixture.gate.verifyActive().block(Duration.ofSeconds(2)))
                .isInstanceOf(TemporalAdmissionUnavailableException.class)
                .hasMessageContaining("global_kill_switch");
        verify(fixture.stub, never()).describeNamespace(any());
    }

    private static Fixture fixture() {
        return fixture(new OperationalKillSwitch(new KillSwitchProperties(null)));
    }

    private static Fixture fixture(OperationalKillSwitch killSwitch) {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        when(factory.newWorker(any(), any())).thenReturn(mock(Worker.class));
        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(factory, queues, "deployment", "build");
        WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
        WorkflowServiceGrpc.WorkflowServiceBlockingStub stub =
                mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
        when(service.blockingStub()).thenReturn(stub);
        when(stub.withDeadlineAfter(2, TimeUnit.SECONDS)).thenReturn(stub);
        TemporalProperties properties = new TemporalProperties("temporal:7233", "ai-factory-local",
                Duration.ofDays(7), "deployment", "build", queues, TemporalProperties.Capacity.defaults(),
                new TemporalProperties.Security(false, "", "", "", ""));
        return new Fixture(factory, stub, new TemporalTicketAdmissionGate(service, factory, registry, properties,
                killSwitch));
    }

    private static Map<String, String> queues() {
        Map<String, String> queues = new LinkedHashMap<>();
        for (String kind : java.util.List.of("workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            queues.put(kind, "ai-factory-" + kind);
        }
        return queues;
    }

    private record Fixture(WorkerFactory factory, WorkflowServiceGrpc.WorkflowServiceBlockingStub stub,
                           TemporalTicketAdmissionGate gate) {}
}
