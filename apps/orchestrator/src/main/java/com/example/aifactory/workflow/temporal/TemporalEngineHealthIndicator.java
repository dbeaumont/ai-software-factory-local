package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Fail-closed readiness for the mandatory Temporal ticket engine. */
@Component("temporalEngine")
public final class TemporalEngineHealthIndicator implements HealthIndicator {
    private static final int REQUIRED_WORKERS = 7;
    private final WorkflowServiceStubs service;
    private final WorkerFactory factory;
    private final TemporalWorkerRegistry registry;
    private final TemporalProperties properties;

    public TemporalEngineHealthIndicator(WorkflowServiceStubs service, WorkerFactory factory,
                                         TemporalWorkerRegistry registry, TemporalProperties properties) {
        this.service = service;
        this.factory = factory;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder health;
        try {
            service.blockingStub().withDeadlineAfter(2, TimeUnit.SECONDS).describeNamespace(
                    DescribeNamespaceRequest.newBuilder().setNamespace(properties.namespace()).build());
            health = factory.isStarted() && !factory.isShutdown()
                    && registry.workers().size() == REQUIRED_WORKERS ? Health.up() : Health.down();
        } catch (RuntimeException unavailable) {
            return Health.down(unavailable)
                    .withDetail("infrastructure", "UNAVAILABLE")
                    .withDetail("ticketEngine", "ADMISSIONS_SUSPENDED")
                    .withDetail("namespace", properties.namespace()).build();
        }
        return health.withDetail("infrastructure", "AVAILABLE")
                .withDetail("ticketEngine", factory.isStarted() && !factory.isShutdown()
                        ? "ACTIVE" : "ADMISSIONS_SUSPENDED")
                .withDetail("namespace", properties.namespace())
                .withDetail("registeredWorkers", registry.workers().size())
                .withDetail("requiredWorkers", REQUIRED_WORKERS).build();
    }
}
