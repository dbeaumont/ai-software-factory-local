package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** Validates and materializes a compact A2A envelope before invoking the role-scoped worker. */
final class AgentExecutionActivitiesImpl implements AgentExecutionActivities {
    private static final String ENVELOPE_SCHEMA = "a2a/schemas/a2a-envelope-v1.schema.json";

    private final AgentExecutionWorker worker;
    private final InputLoader inputs;
    private final ObjectMapper mapper;
    private final Schema schema;

    AgentExecutionActivitiesImpl(AgentExecutionWorker worker, AgentInputEvidenceReader inputs,
                                 ObjectMapper mapper) {
        this(worker, inputs::read, mapper);
    }

    AgentExecutionActivitiesImpl(AgentExecutionWorker worker, InputLoader inputs, ObjectMapper mapper) {
        this.worker = worker;
        this.inputs = inputs;
        this.mapper = mapper;
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(ENVELOPE_SCHEMA)) {
            if (source == null) throw new IllegalStateException("Missing " + ENVELOPE_SCHEMA);
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    ignored -> {}).getSchema(source);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load A2A envelope schema", failure);
        }
    }

    @Override
    public Result execute(Command command) {
        requireCommand(command);
        JsonNode envelope = parse(command.envelopeJson());
        if (!schema.validate(envelope).isEmpty()) {
            throw new IllegalArgumentException("A2A execution envelope violates a2a-envelope-v1");
        }
        String role = required(envelope, "target_role");
        String skill = required(envelope, "skill_id");
        if (!command.role().equals(role) || !command.skill().equals(skill)
                || !skill.startsWith(role + ".")) {
            throw new SecurityException("A2A execution envelope changed its admitted role or skill");
        }
        JsonNode constraints = envelope.path("constraints");
        String outputContract = required(constraints, "expected_output_contract");
        long maximumInputBytes = constraints.path("max_input_bytes").asLong(-1);
        Set<String> allowedReferences = new LinkedHashSet<>();
        constraints.path("allowed_reference_ids").forEach(value -> allowedReferences.add(value.asText()));

        JsonNode references = envelope.path("input_references");
        if (references.isEmpty()) {
            throw new IllegalArgumentException("A2A agent execution requires a primary input reference");
        }
        for (JsonNode admitted : references) {
            if (!allowedReferences.contains(required(admitted, "reference_id"))) {
                throw new SecurityException("A2A input reference is outside the admitted reference set");
            }
        }
        JsonNode reference = references.get(0);
        AgentInputEvidenceReader.Reference inputReference = new AgentInputEvidenceReader.Reference(
                required(reference, "reference_id"), required(reference, "uri"), required(reference, "digest"),
                reference.path("size_bytes").asLong(-1), required(reference, "contract"));
        if (!skill.equals(role + "." + inputReference.contract())) {
            throw new SecurityException("A2A primary input is outside the admitted skill or reference set");
        }
        String attemptId = attemptId(command.taskId(), inputReference.uri());
        JsonNode input = inputs.read(command.taskId(), attemptId, inputReference, maximumInputBytes);
        JsonNode budget = envelope.path("budget");
        AgentExecutionWorker.Result executed = worker.execute(new AgentExecutionWorker.Request(
                command.taskId(), attemptId, role, inputReference.contract(), input, outputContract,
                allowedReferences, new AgentLoop.Budget(budget.path("max_turns").asInt(),
                Duration.ofSeconds(budget.path("timeout_seconds").asLong()), budget.path("max_tokens").asInt(),
                budget.path("max_cost_micros").asLong()), "HIERARCHICAL_ACTIVE",
                command.traceparent(), command.baggage()));
        try {
            byte[] content = mapper.writeValueAsBytes(executed.document());
            return new Result(attemptId, outputContract, allowedReferences,
                    Base64.getEncoder().encodeToString(content), Digests.sha256(content));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize validated agent output", failure);
        }
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("A2A execution envelope is not valid JSON", failure);
        }
    }

    private static void requireCommand(Command command) {
        if (command == null || command.taskId() == null || command.taskId().isBlank()
                || command.role() == null || command.role().isBlank()
                || command.skill() == null || command.skill().isBlank()
                || command.envelopeJson() == null || command.envelopeJson().isBlank()) {
            throw new IllegalArgumentException("Agent execution activity command is incomplete");
        }
    }

    private static String attemptId(String taskId, String uri) {
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            String[] path = parsed.getPath() == null ? new String[0] : parsed.getPath().split("/");
            if (!"evidence".equals(parsed.getScheme()) || !taskId.equals(parsed.getHost()) || path.length != 4
                    || !path[1].matches("[A-Za-z0-9_-]{1,128}")) {
                throw new SecurityException("A2A input Evidence URI is outside the task context");
            }
            return path[1];
        } catch (IllegalArgumentException malformed) {
            throw new SecurityException("A2A input Evidence URI is invalid", malformed);
        }
    }

    private static String required(JsonNode value, String field) {
        String text = value.path(field).asText("");
        if (text.isBlank()) throw new IllegalArgumentException("A2A execution envelope lacks " + field);
        return text;
    }

    @FunctionalInterface
    interface InputLoader {
        JsonNode read(String taskId, String attemptId, AgentInputEvidenceReader.Reference reference,
                      long maximumInputBytes);
    }
}
