package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministically detects incompatible normalized conclusions emitted by distinct specialist perimeters. */
@Component
public final class CrossPerimeterContradictionDetector {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,255}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    public List<Candidate> detect(Request request) {
        requireRequest(request);
        Map<String, List<NormalizedAssertion>> bySubject = new HashMap<>();
        Set<String> resultIds = new HashSet<>();

        for (SpecialistResult result : request.results()) {
            requireResult(request, result);
            if (!resultIds.add(result.resultId())) {
                throw new IllegalArgumentException("Specialist result identifiers must be unique");
            }
            for (Assertion assertion : result.assertions()) {
                NormalizedAssertion normalized = normalize(result, assertion);
                bySubject.computeIfAbsent(normalized.subject(), ignored -> new ArrayList<>()).add(normalized);
            }
        }

        return bySubject.entrySet().stream()
                .filter(entry -> isCrossPerimeterConflict(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> candidate(request, entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Candidate candidate(Request request, String subject, List<NormalizedAssertion> assertions) {
        Set<Dimension> dimensions = new HashSet<>(assertions.stream().map(NormalizedAssertion::dimension).toList());
        if (dimensions.size() != 1) {
            throw new IllegalArgumentException("Assertions for one subject must use one contradiction dimension");
        }
        List<Source> sources = assertions.stream()
                .sorted(Comparator.comparing((NormalizedAssertion value) -> value.perimeter().name())
                        .thenComparing(NormalizedAssertion::resultId)
                        .thenComparing(NormalizedAssertion::conclusion))
                .map(value -> new Source(value.resultId(), value.perimeter(), value.role(),
                        value.conclusion(), value.resultDigest(), value.evidenceUris()))
                .toList();
        if (sources.size() > 8) {
            throw new IllegalArgumentException("A contradiction cannot reference more than eight sources");
        }
        String identity = request.taskId() + '\n' + request.attemptId() + '\n' + subject + '\n'
                + sources.stream().map(source -> source.perimeter() + ":" + source.resultId() + ":"
                        + source.conclusion() + ":" + source.resultDigest()).reduce("", (left, right) -> left + right + '\n');
        return new Candidate("contradiction-" + sha256(identity).substring(0, 24), request.taskId(),
                request.attemptId(), subject, dimensions.iterator().next(), sources);
    }

    private static boolean isCrossPerimeterConflict(List<NormalizedAssertion> assertions) {
        for (int left = 0; left < assertions.size(); left++) {
            for (int right = left + 1; right < assertions.size(); right++) {
                NormalizedAssertion first = assertions.get(left);
                NormalizedAssertion second = assertions.get(right);
                if (first.perimeter() != second.perimeter()
                        && !first.conclusion().equals(second.conclusion())) return true;
            }
        }
        return false;
    }

    private static NormalizedAssertion normalize(SpecialistResult result, Assertion assertion) {
        if (assertion == null || assertion.subject() == null || assertion.dimension() == null
                || assertion.conclusion() == null) {
            throw new IllegalArgumentException("Specialist assertion is incomplete");
        }
        String subject = assertion.subject().strip().toLowerCase(Locale.ROOT);
        String conclusion = assertion.conclusion().strip().toUpperCase(Locale.ROOT);
        if (!SUBJECT.matcher(subject).matches() || conclusion.isEmpty() || conclusion.length() > 256) {
            throw new IllegalArgumentException("Specialist assertion is invalid");
        }
        return new NormalizedAssertion(subject, assertion.dimension(), conclusion, result.resultId(),
                result.perimeter(), result.role(), result.resultDigest(), result.evidenceUris());
    }

    private static void requireRequest(Request request) {
        if (request == null || !validId(request.taskId()) || !validId(request.attemptId())
                || request.results() == null || request.results().size() < 2) {
            throw new IllegalArgumentException("Contradiction detection request is invalid");
        }
        long perimeters = request.results().stream().filter(result -> result != null)
                .map(SpecialistResult::perimeter).distinct().count();
        if (perimeters < 2) throw new IllegalArgumentException("At least two specialist perimeters are required");
    }

    private static void requireResult(Request request, SpecialistResult result) {
        if (result == null || result.perimeter() == null || !validId(result.resultId())
                || !validId(result.taskId()) || !validId(result.attemptId())
                || !request.taskId().equals(result.taskId()) || !request.attemptId().equals(result.attemptId())
                || result.role() == null || result.role().isBlank() || result.role().length() > 64
                || result.resultDigest() == null || !DIGEST.matcher(result.resultDigest()).matches()
                || result.evidenceUris() == null || result.evidenceUris().size() > 16
                || result.evidenceUris().stream().anyMatch(uri -> uri == null || !uri.startsWith("evidence://")
                || uri.length() > 1024) || result.assertions() == null) {
            throw new IllegalArgumentException("Specialist result is invalid or outside the workflow attempt");
        }
    }

    private static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Request(String taskId, String attemptId, List<SpecialistResult> results) {
        public Request {
            results = results == null ? null : List.copyOf(results);
        }
    }

    public record SpecialistResult(String resultId, String taskId, String attemptId, Perimeter perimeter,
                                   String role, String resultDigest, List<String> evidenceUris,
                                   List<Assertion> assertions) {
        public SpecialistResult {
            evidenceUris = evidenceUris == null ? null : List.copyOf(evidenceUris);
            assertions = assertions == null ? null : List.copyOf(assertions);
        }
    }

    public record Assertion(String subject, Dimension dimension, String conclusion) {}

    public record Candidate(String contradictionId, String taskId, String attemptId, String subject,
                            Dimension dimension, List<Source> sources) {
        public Candidate {
            sources = List.copyOf(sources);
        }
    }

    public record Source(String resultId, Perimeter perimeter, String role, String conclusion,
                         String resultDigest, List<String> evidenceUris) {
        public Source {
            evidenceUris = List.copyOf(evidenceUris);
        }
    }

    public enum Perimeter { ARCHITECTURE, CODE, TESTS, SECURITY }

    public enum Dimension { FACT, SCOPE, RISK, TEST_COVERAGE, RECOMMENDATION }

    private record NormalizedAssertion(String subject, Dimension dimension, String conclusion, String resultId,
                                       Perimeter perimeter, String role, String resultDigest,
                                       List<String> evidenceUris) {}
}
