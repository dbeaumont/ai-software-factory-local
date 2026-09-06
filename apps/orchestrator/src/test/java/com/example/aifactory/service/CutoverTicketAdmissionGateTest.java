package com.example.aifactory.service;

import com.example.aifactory.a2a.A2aFleetReadinessHealthIndicator;
import com.example.aifactory.workflow.temporal.TemporalAdmissionUnavailableException;
import com.example.aifactory.workflow.temporal.TemporalTicketAdmissionGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CutoverTicketAdmissionGateTest {
    @Test
    void refusesBeforeTemporalChecksWhenOperatorClosedAdmissions() {
        AdmissionControl control = mock(AdmissionControl.class);
        TemporalTicketAdmissionGate temporal = mock(TemporalTicketAdmissionGate.class);
        A2aFleetReadinessHealthIndicator fleet = mock(A2aFleetReadinessHealthIndicator.class);
        when(control.status()).thenReturn(new AdmissionControl.Status(
                false, "temporal_cutover", 2, Instant.parse("2026-09-06T00:00:00Z")));

        CutoverTicketAdmissionGate gate = new CutoverTicketAdmissionGate(control, temporal, fleet);

        assertThatThrownBy(() -> gate.verifyActive().block(Duration.ofSeconds(2)))
                .isInstanceOf(TemporalAdmissionUnavailableException.class)
                .hasMessageContaining("suspended for maintenance")
                .hasMessageContaining("revision 2");
        verify(temporal, never()).verifyActive();
        verify(fleet, never()).health();
    }

    @Test
    void delegatesToTemporalWhenOperatorOpenedAdmissions() {
        AdmissionControl control = mock(AdmissionControl.class);
        TemporalTicketAdmissionGate temporal = mock(TemporalTicketAdmissionGate.class);
        A2aFleetReadinessHealthIndicator fleet = mock(A2aFleetReadinessHealthIndicator.class);
        when(control.status()).thenReturn(new AdmissionControl.Status(
                true, "normal_operation", 3, Instant.parse("2026-09-06T00:00:00Z")));
        when(temporal.verifyActive()).thenReturn(Mono.empty());
        when(fleet.health()).thenReturn(Health.up().build());

        CutoverTicketAdmissionGate gate = new CutoverTicketAdmissionGate(control, temporal, fleet);

        assertThatCode(() -> gate.verifyActive().block(Duration.ofSeconds(2))).doesNotThrowAnyException();
        verify(temporal).verifyActive();
        verify(fleet).health();
    }

    @Test
    void suspendsAdmissionsWithoutCallingTemporalWhenA2aFleetIsUnavailable() {
        AdmissionControl control = mock(AdmissionControl.class);
        TemporalTicketAdmissionGate temporal = mock(TemporalTicketAdmissionGate.class);
        A2aFleetReadinessHealthIndicator fleet = mock(A2aFleetReadinessHealthIndicator.class);
        when(control.status()).thenReturn(new AdmissionControl.Status(
                true, "normal_operation", 4, Instant.parse("2026-09-06T00:00:00Z")));
        when(fleet.health()).thenReturn(Health.down().withDetail("blockers", "sanitized").build());

        CutoverTicketAdmissionGate gate = new CutoverTicketAdmissionGate(control, temporal, fleet);

        assertThatThrownBy(() -> gate.verifyActive().block(Duration.ofSeconds(2)))
                .isInstanceOf(TemporalAdmissionUnavailableException.class)
                .hasMessage("A2A agent fleet is unavailable; ticket admissions are suspended");
        verify(temporal, never()).verifyActive();
    }
}
