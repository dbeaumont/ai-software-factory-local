package com.example.aifactory.a2a;

import java.util.Set;

/** Media types accepted at the first A2A cutover boundary. */
public final class A2aMediaTypes {

    public static final String TEXT = "text/plain";
    public static final String JSON = "application/json";
    public static final String EVIDENCE_REFERENCE = "application/vnd.ai-factory.evidence-reference+json";

    private static final Set<String> SUPPORTED = Set.of(TEXT, JSON, EVIDENCE_REFERENCE);

    private A2aMediaTypes() {
    }

    public static boolean isSupported(String mediaType) {
        return SUPPORTED.contains(mediaType);
    }
}
