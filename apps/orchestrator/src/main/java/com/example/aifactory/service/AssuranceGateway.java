package com.example.aifactory.service;

import com.example.aifactory.config.AssuranceClientProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AssuranceGateway {
    private final McpToolInvoker mcp;
    private final McpResponseValidator validator;
    private final AssuranceClientProperties properties;

    public AssuranceGateway(McpToolInvoker mcp, McpResponseValidator validator,
                            AssuranceClientProperties properties) {
        this.mcp = mcp;
        this.validator = validator;
        this.properties = properties;
    }

    public JsonNode requireQualityGate(String taskId, String sourceCommit, String qualityEvidence) {
        if (!properties.enabled()) throw new IllegalStateException("assurance MCP is disabled");
        if (qualityEvidence == null) throw new IllegalStateException("quality evidence is absent");
        String attemptId = "pipeline-1";
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", taskId);
        arguments.put("attempt_id", attemptId);
        arguments.put("source_commit", sourceCommit);
        arguments.put("provider", "SonarQube");
        arguments.put("gate", "default");
        arguments.put("technical_status", "SUCCEEDED");
        arguments.put("exit_code", 0);
        arguments.put("evidence_status", "COMPLETE");
        arguments.put("output", qualityEvidence);
        arguments.put("evidence_uri", "evidence://" + taskId + '/' + attemptId + "/quality");
        arguments.put("evidence_digest", digest(qualityEvidence));
        JsonNode result = validator.validate("assurance.evaluate_quality_gate",
                mcp.call(properties.serverName(), "assurance.evaluate_quality_gate", arguments));
        if (!"PASSED".equals(result.path("verdict").asText())) {
            throw new IllegalStateException("Quality gate blocks delivery: " + result.path("verdict").asText());
        }
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("quality evidence digest failed", exception);
        }
    }
}
