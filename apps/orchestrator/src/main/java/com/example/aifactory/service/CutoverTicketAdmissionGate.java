package com.example.aifactory.service;

import com.example.aifactory.workflow.temporal.TemporalAdmissionUnavailableException;
import com.example.aifactory.workflow.temporal.TemporalTicketAdmissionGate;
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

    public CutoverTicketAdmissionGate(AdmissionControl admissionControl, TemporalTicketAdmissionGate temporal) {
        this.admissionControl = admissionControl;
        this.temporal = temporal;
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
                    return temporal.verifyActive();
                });
    }
}
