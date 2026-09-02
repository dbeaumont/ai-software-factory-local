package com.example.aifactory.service;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds a digest-bound human decision request for open product, architecture, security or data choices. */
@Component
public final class HumanDecisionEscalator {
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern OPTION_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Map<DecisionDomain, Set<ContradictionClassifier.Classification>> DOMAINS = Map.of(
            DecisionDomain.PRODUCT, Set.of(ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION),
            DecisionDomain.ARCHITECTURE, Set.of(ContradictionClassifier.Classification.INCOMPATIBLE_SCOPE,
                    ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION),
            DecisionDomain.SECURITY, Set.of(ContradictionClassifier.Classification.RISK,
                    ContradictionClassifier.Classification.FACTUAL),
            DecisionDomain.DATA, Set.of(ContradictionClassifier.Classification.INCOMPATIBLE_SCOPE,
                    ContradictionClassifier.Classification.RISK, ContradictionClassifier.Classification.FACTUAL));

    public Escalation escalate(ContradictionClassifier.ClassifiedCandidate contradiction,
                               DeterministicContradictionResolver.Result resolution,
                               DecisionDomain domain, String objectDigest, String question,
                               List<Option> suppliedOptions) {
        requireInputs(contradiction, resolution, domain, objectDigest, question, suppliedOptions);
        List<Option> options = suppliedOptions.stream().sorted(Comparator.comparing(Option::optionId)).toList();
        if (options.stream().map(Option::optionId).distinct().count() != options.size()) {
            throw new IllegalArgumentException("Human decision option identifiers must be unique");
        }
        List<String> evidenceUris = contradiction.candidate().sources().stream()
                .flatMap(source -> source.evidenceUris().stream()).distinct().sorted().toList();
        String requestId = "decision-" + sha256(contradiction.candidate().contradictionId() + '\n'
                + domain + '\n' + objectDigest).substring(0, 24);
        Set<String> allowed = new LinkedHashSet<>(options.stream().map(Option::optionId).toList());
        SoftwareFactoryWorkflow.HumanDecisionRequest workflowRequest =
                new SoftwareFactoryWorkflow.HumanDecisionRequest(requestId, question, allowed, evidenceUris,
                        objectDigest, Set.of(domain.name()));
        return new Escalation(requestId, contradiction.candidate().contradictionId(), domain, objectDigest,
                options, evidenceUris, workflowRequest);
    }

    private static void requireInputs(ContradictionClassifier.ClassifiedCandidate contradiction,
                                      DeterministicContradictionResolver.Result resolution,
                                      DecisionDomain domain, String objectDigest, String question,
                                      List<Option> options) {
        if (contradiction == null || contradiction.candidate() == null || contradiction.classification() == null
                || resolution == null || resolution.outcome() == DeterministicContradictionResolver.Outcome.RESOLVED) {
            throw new IllegalArgumentException("Only an unresolved contradiction can be escalated");
        }
        if (domain == null || !DOMAINS.get(domain).contains(contradiction.classification())) {
            throw new IllegalArgumentException("Human decision domain does not own this contradiction");
        }
        if (objectDigest == null || !DIGEST.matcher(objectDigest).matches()
                || question == null || question.isBlank() || question.length() > 1_000
                || options == null || options.size() < 2 || options.size() > 5) {
            throw new IllegalArgumentException("Human decision request is invalid");
        }
        if (options.stream().anyMatch(option -> option == null || option.optionId() == null
                || !OPTION_ID.matcher(option.optionId()).matches() || option.label() == null
                || option.label().isBlank() || option.label().length() > 120 || option.description() == null
                || option.description().isBlank() || option.description().length() > 2_000)) {
            throw new IllegalArgumentException("Human decision option is invalid");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Option(String optionId, String label, String description, boolean reversible) {}

    public record Escalation(String requestId, String contradictionId, DecisionDomain domain,
                             String objectDigest, List<Option> options, List<String> evidenceUris,
                             SoftwareFactoryWorkflow.HumanDecisionRequest workflowRequest) {
        public Escalation {
            options = List.copyOf(options);
            evidenceUris = List.copyOf(evidenceUris);
        }
    }

    public enum DecisionDomain { PRODUCT, ARCHITECTURE, SECURITY, DATA }
}
