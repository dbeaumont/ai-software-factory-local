package com.example.aifactory.service;

import reactor.core.publisher.Mono;

/** Mandatory asynchronous gate evaluated before a ticket is persisted or started. */
@FunctionalInterface
public interface TicketAdmissionGate {
    Mono<Void> verifyActive();
}
