package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.service.OperationalKillSwitch;
import com.example.aifactory.service.TicketAdmissionGate;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeUnit;

/** Refuses new work whenever the mandatory Temporal engine is not fully operational. */
@Component
public final class TemporalTicketAdmissionGate implements TicketAdmissionGate {
    private static final int REQUIRED_WORKERS = 7;
    private final WorkflowServiceStubs service;
    private final WorkerFactory factory;
    private final TemporalWorkerRegistry registry;
    private final TemporalProperties properties;
    private final OperationalKillSwitch killSwitch;

    public TemporalTicketAdmissionGate(WorkflowServiceStubs service, WorkerFactory factory,
                                       TemporalWorkerRegistry registry, TemporalProperties properties,
                                       OperationalKillSwitch killSwitch) {
        this.service = service;
        this.factory = factory;
        this.registry = registry;
        this.properties = properties;
        this.killSwitch = killSwitch;
    }

    @Override
    public Mono<Void> verifyActive() {
        return Mono.fromCallable(() -> {
                    OperationalKillSwitch.Decision admission = killSwitch.decision(
                            "temporal", "workflow.start", "workflow");
                    if (!admission.allowed()) {
                        throw new TemporalAdmissionUnavailableException(
                                "Factory admissions are suspended by " + admission.reason());
                    }
                    if (!factory.isStarted() || factory.isShutdown()
                            || registry.workers().size() != REQUIRED_WORKERS) {
                        throw new TemporalAdmissionUnavailableException(
                                "Temporal ticket engine is not active; admissions are suspended");
                    }
                    try {
                        service.blockingStub().withDeadlineAfter(2, TimeUnit.SECONDS).describeNamespace(
                                DescribeNamespaceRequest.newBuilder().setNamespace(properties.namespace()).build());
                    } catch (RuntimeException unavailable) {
                        throw new TemporalAdmissionUnavailableException(
                                "Temporal namespace is unavailable; admissions are suspended", unavailable);
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
