package com.example.aifactory.service;

import java.util.concurrent.atomic.AtomicLong;

/** Test-only fallback used by unit-constructed services; production uses PostgreSQL. */
final class InMemoryTicketNumberGenerator implements TicketNumberGenerator {
    private final AtomicLong sequence = new AtomicLong(1);

    @Override
    public String next() {
        return "AF-%04d".formatted(sequence.getAndIncrement());
    }
}
