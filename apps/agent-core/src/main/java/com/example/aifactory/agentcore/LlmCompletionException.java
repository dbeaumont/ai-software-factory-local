package com.example.aifactory.agentcore;

/** Stable failure classification independent of the selected LLM provider. */
public final class LlmCompletionException extends RuntimeException {
    private final String reason;
    private final boolean retryable;

    public LlmCompletionException(String reason, boolean retryable, String message) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    public LlmCompletionException(String reason, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public String reason() { return reason; }
    public boolean retryable() { return retryable; }
}
