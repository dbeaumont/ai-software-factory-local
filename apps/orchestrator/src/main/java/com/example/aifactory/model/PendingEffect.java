package com.example.aifactory.model;

import java.util.Map;

public record PendingEffect(String tool, Map<String, String> safeArguments, String impact,
                            String policyDecision, boolean confirmationRequired,
                            String manifestId, String manifestUri, String manifestDigest) {
    public PendingEffect {
        safeArguments = safeArguments == null ? Map.of() : Map.copyOf(safeArguments);
    }

    public PendingEffect(String tool, Map<String, String> safeArguments, String impact,
                         String policyDecision, boolean confirmationRequired) {
        this(tool, safeArguments, impact, policyDecision, confirmationRequired, null, null, null);
    }
}
