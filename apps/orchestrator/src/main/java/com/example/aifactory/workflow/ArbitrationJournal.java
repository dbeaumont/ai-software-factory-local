package com.example.aifactory.workflow;

import java.util.List;
import java.util.Optional;

/** Append-only persistence port for auditable consolidation arbitrations. */
public interface ArbitrationJournal {
    void append(Entry entry);

    Optional<Entry> find(String arbitrationId);

    List<Entry> list(String taskId, String attemptId);

    record Entry(String arbitrationId, String taskId, String attemptId, String sourceCommit,
                 String contradictionId, String ruleId, String ruleVersion, String decision,
                 String author, AuthorType authorType, List<InputReference> inputs,
                 List<EvidenceReference> evidence, String recordDigest, String decidedAt) {
        public Entry {
            inputs = List.copyOf(inputs);
            evidence = List.copyOf(evidence);
        }
    }

    record InputReference(String inputId, String digest) {}

    record EvidenceReference(String uri, String digest) {}

    enum AuthorType { WORKFLOW, POLICY, AGENT, INDEPENDENT_REVIEWER, HUMAN }
}
