package com.example.aifactory.service;

import com.example.aifactory.workflow.ArbitrationJournal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArbitrationRecorderTest {
    private final InMemoryArbitrationJournal journal = new InMemoryArbitrationJournal();
    private final ArbitrationRecorder recorder = new ArbitrationRecorder(journal);

    @Test
    void recordsInputsRuleDecisionAuthorAndEvidenceInCanonicalOrder() {
        ArbitrationRecorder.Request request = request("ALLOW", "policy");

        ArbitrationJournal.Entry first = recorder.record(request);
        ArbitrationJournal.Entry replay = recorder.record(request);

        assertThat(first).isEqualTo(replay);
        assertThat(first.arbitrationId()).startsWith("arbitration-").hasSize(36);
        assertThat(first.recordDigest()).hasSize(64);
        assertThat(first.ruleId()).isEqualTo("factual.policy_wins");
        assertThat(first.author()).isEqualTo("policy");
        assertThat(first.inputs()).extracting(ArbitrationJournal.InputReference::inputId)
                .containsExactly("input-a", "input-b");
        assertThat(first.evidence()).extracting(ArbitrationJournal.EvidenceReference::uri)
                .containsExactly("evidence://task-1/a", "evidence://task-1/b");
        assertThat(journal.list("task-1", "attempt-1")).containsExactly(first);
    }

    @Test
    void digestChangesWhenAnyArbitratedDecisionInputChanges() {
        ArbitrationJournal.Entry allowed = recorder.record(request("ALLOW", "policy"));
        ArbitrationJournal.Entry denied = recorder.record(request("DENY", "policy"));

        assertThat(allowed.recordDigest()).isNotEqualTo(denied.recordDigest());
        assertThat(allowed.arbitrationId()).isNotEqualTo(denied.arbitrationId());
    }

    @Test
    void rejectsMissingEvidenceAndDuplicateInputReferences() {
        ArbitrationRecorder.Request valid = request("ALLOW", "policy");
        assertThatThrownBy(() -> recorder.record(new ArbitrationRecorder.Request(valid.taskId(),
                valid.attemptId(), valid.sourceCommit(), valid.contradictionId(), valid.ruleId(),
                valid.ruleVersion(), valid.decision(), valid.author(), valid.authorType(), valid.inputs(),
                List.of(), valid.decidedAt()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.record(new ArbitrationRecorder.Request(valid.taskId(),
                valid.attemptId(), valid.sourceCommit(), valid.contradictionId(), valid.ruleId(),
                valid.ruleVersion(), valid.decision(), valid.author(), valid.authorType(),
                List.of(valid.inputs().getFirst(), valid.inputs().getFirst()), valid.evidence(), valid.decidedAt())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input references");
    }

    private static ArbitrationRecorder.Request request(String decision, String author) {
        return new ArbitrationRecorder.Request("task-1", "attempt-1", "a".repeat(40), "contradiction-1",
                "factual.policy_wins", "1", decision, author, ArbitrationJournal.AuthorType.POLICY,
                List.of(new ArbitrationJournal.InputReference("input-b", "b".repeat(64)),
                        new ArbitrationJournal.InputReference("input-a", "a".repeat(64))),
                List.of(new ArbitrationJournal.EvidenceReference("evidence://task-1/b", "d".repeat(64)),
                        new ArbitrationJournal.EvidenceReference("evidence://task-1/a", "c".repeat(64))),
                "2026-09-02T20:00:00Z");
    }
}
