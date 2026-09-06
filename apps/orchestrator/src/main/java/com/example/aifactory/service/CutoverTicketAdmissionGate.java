package com.example.aifactory.service;

import com.example.aifactory.a2a.A2aFleetReadinessHealthIndicator;
import com.example.aifactory.config.A2aFleetProperties;
import com.example.aifactory.workflow.temporal.TemporalAdmissionUnavailableException;
import com.example.aifactory.workflow.temporal.TemporalTicketAdmissionGate;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Applies the durable operator switch before the qualified Temporal availability gate. */
@Component
@Primary
public final class CutoverTicketAdmissionGate implements TicketAdmissionGate {
    private final AdmissionControl admissionControl;
    private final TemporalTicketAdmissionGate temporal;
    private final A2aFleetReadinessHealthIndicator a2aFleet;
    private final A2aFleetProperties a2a;

    public CutoverTicketAdmissionGate(AdmissionControl admissionControl, TemporalTicketAdmissionGate temporal,
                                      A2aFleetReadinessHealthIndicator a2aFleet, A2aFleetProperties a2a) {
        this.admissionControl = admissionControl;
        this.temporal = temporal;
        this.a2aFleet = a2aFleet;
        this.a2a = a2a;
    }

    @Override
    public Mono<Void> verifyActive() {
        return Mono.fromCallable(admissionControl::status)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(control -> {
                    if (!control.admissionsOpen()) {
                        return Mono.error(new TemporalAdmissionUnavailableException(
                                "Ticket admissions are suspended for maintenance (revision "
                                        + control.revision() + ", reason: " + control.reason() + ")"));
                    }
                    return requireA2aFleet().then(Mono.defer(temporal::verifyActive));
                });
    }

    private Mono<Void> requireA2aFleet() {
        if (!a2a.enabled()) return Mono.empty();
        return Mono.fromCallable(a2aFleet::health)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(health -> Status.UP.equals(health.getStatus())
                        ? Mono.empty() : Mono.error(new TemporalAdmissionUnavailableException(
                        "A2A agent fleet is unavailable; ticket admissions are suspended")));
    }
}
