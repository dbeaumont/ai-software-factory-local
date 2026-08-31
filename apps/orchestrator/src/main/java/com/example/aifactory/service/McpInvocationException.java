package com.example.aifactory.service;

public final class McpInvocationException extends IllegalStateException {
    private final String code;
    private final boolean retryable;

    public McpInvocationException(String code, boolean retryable, String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.retryable = retryable;
    }

    public McpInvocationException(String code, boolean retryable, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
