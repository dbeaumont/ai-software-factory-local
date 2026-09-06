package com.example.aifactory.service;

import java.util.Optional;

/** Persists the identity of an external sandbox job in the enclosing durable activity. */
public interface SandboxActivityHeartbeat {
    Optional<Checkpoint> latest();

    void record(Checkpoint checkpoint);

    static SandboxActivityHeartbeat noop() {
        return Noop.INSTANCE;
    }

    record Checkpoint(String effectKey, String operation, String executionId) {
        public Checkpoint {
            if (effectKey == null || !effectKey.matches("effect-[0-9a-f]{64}")
                    || operation == null || !operation.matches("[a-z][a-z-]{2,31}")
                    || executionId == null || !executionId.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Invalid sandbox activity heartbeat checkpoint");
            }
        }
    }

    enum Noop implements SandboxActivityHeartbeat {
        INSTANCE;

        @Override
        public Optional<Checkpoint> latest() {
            return Optional.empty();
        }

        @Override
        public void record(Checkpoint checkpoint) {
            // Unit tests and non-activity health paths do not own a Temporal activity context.
        }
    }
}
