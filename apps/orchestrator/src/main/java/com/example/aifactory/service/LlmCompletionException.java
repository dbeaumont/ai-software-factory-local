package com.example.aifactory.service;

final class LlmCompletionException extends IllegalStateException {
    private final String reason;
    private final boolean retryable;

    LlmCompletionException(String reason, boolean retryable, String message) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    String reason() {
        return reason;
    }

    boolean retryable() {
        return retryable;
    }
}
