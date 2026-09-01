package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTransportSecurityValidatorTest {
    @Test
    void requiresHttpsForNonLoopbackOutsideDevelopment() {
        assertThrows(IllegalStateException.class,
                () -> McpTransportSecurityValidator.validate("context", URI.create("http://context.internal:8091"), false));
        assertDoesNotThrow(
                () -> McpTransportSecurityValidator.validate("context", URI.create("https://context.internal"), false));
        assertDoesNotThrow(
                () -> McpTransportSecurityValidator.validate("context", URI.create("http://127.0.0.1:8091"), false));
        assertDoesNotThrow(
                () -> McpTransportSecurityValidator.validate("context", URI.create("http://context-mcp:8091"), true));
    }
}
