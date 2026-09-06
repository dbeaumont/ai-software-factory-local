package com.example.aifactory.a2a;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure construction boundary that keeps URI mechanics out of deterministic Temporal workflow sources. */
public final class A2aEvidencePartFactory {
    private A2aEvidencePartFactory() { }

    public static A2aContracts.Part reference(String id, String uri, String digest, String contract) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", "1");
        data.put("reference_id", id);
        data.put("uri", uri);
        data.put("digest", digest);
        data.put("media_type", "application/json");
        data.put("classification", "INTERNAL");
        data.put("contract", contract);
        data.put("contract_version", "1");
        return new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null, Map.copyOf(data), URI.create(uri));
    }
}
