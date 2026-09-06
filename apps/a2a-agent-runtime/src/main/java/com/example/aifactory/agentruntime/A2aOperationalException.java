package com.example.aifactory.agentruntime;

/** Typed operational failure safe to classify at the A2A transport boundary. */
public final class A2aOperationalException extends RuntimeException {
    private final Category category;

    public A2aOperationalException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() { return category; }

    public enum Category { DEPENDENCY, TIMEOUT, QUOTA }
}
