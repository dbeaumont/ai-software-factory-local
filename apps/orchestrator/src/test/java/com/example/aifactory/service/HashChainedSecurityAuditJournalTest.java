package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainedSecurityAuditJournalTest {
    @Test
    void signsAndChainsEveryRequiredSecurityEvent() {
        HashChainedSecurityAuditJournal journal = new HashChainedSecurityAuditJournal(new byte[32]);

        journal.append(SecurityAuditJournal.EventType.AUTHORIZATION, "task-1", "developer",
                "context.read_file", "ALLOW");
        journal.append(SecurityAuditJournal.EventType.REFUSAL, "task-1", "developer",
                "scm.create_draft_pull_request", "DENY");
        journal.append(SecurityAuditJournal.EventType.APPROVAL, "task-1", "human-approver",
                "a".repeat(64), "APPROVE");
        journal.append(SecurityAuditJournal.EventType.MODE_CHANGE, "task-1", "operator",
                "execution-mode", "PIPELINE");

        assertThat(journal.list()).hasSize(4);
        assertThat(journal.list().get(1).previousDigest()).isEqualTo(journal.list().get(0).digest());
        assertThat(journal.list()).allSatisfy(entry -> assertThat(entry.digest()).matches("[0-9a-f]{64}"));
        assertThat(journal.verifyIntegrity()).isTrue();

        SecurityAuditJournal.Entry original = journal.list().get(2);
        SecurityAuditJournal.Entry altered = new SecurityAuditJournal.Entry(original.sequence(), original.type(),
                original.taskId(), original.actor(), original.objectReference(), "DENY", original.occurredAt(),
                original.previousDigest(), original.digest());
        assertThat(journal.verify(List.of(journal.list().get(0), journal.list().get(1), altered,
                journal.list().get(3)))).isFalse();
    }
}
