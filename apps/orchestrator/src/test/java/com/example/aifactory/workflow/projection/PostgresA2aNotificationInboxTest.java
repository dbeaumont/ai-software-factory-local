package com.example.aifactory.workflow.projection;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aNotificationInbox;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class PostgresA2aNotificationInboxTest {

    @Test
    void exactCallbackReplayKeepsCanonicalDigestIdempotency() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresA2aNotificationInbox inbox = inbox(jdbc);
        Object existing = row("context-1", "COMPLETED", "digest-1", "SIGNALLED");
        doReturn(List.of(existing)).when(jdbc).query(
                contains("FROM a2a_notification_inbox"), any(RowMapper.class),
                eq("developer"), eq("agent-task-1"), eq(2L));

        A2aNotificationInbox.Admission admission = inbox.admit(
                association(), notification(2), "digest-1", "{}");

        assertThat(admission.shouldSignal()).isFalse();
    }

    @Test
    void modifiedCallbackReplayIsRejectedBeforeTemporalSignalling() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresA2aNotificationInbox inbox = inbox(jdbc);
        Object existing = row("context-1", "COMPLETED", "digest-original", "SIGNALLED");
        doReturn(List.of(existing)).when(jdbc).query(
                contains("FROM a2a_notification_inbox"), any(RowMapper.class),
                eq("developer"), eq("agent-task-1"), eq(2L));

        assertThatThrownBy(() -> inbox.admit(association(), notification(2), "digest-tampered", "{}"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Divergent A2A notification replay");
    }

    @Test
    void callbackOlderThanTheHighestAdmittedSequenceIsRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresA2aNotificationInbox inbox = inbox(jdbc);
        doReturn(List.of()).when(jdbc).query(
                contains("FROM a2a_notification_inbox"), any(RowMapper.class),
                eq("developer"), eq("agent-task-1"), eq(1L));
        when(jdbc.queryForObject(contains("COALESCE(MAX"), eq(Long.class),
                eq("developer"), eq("agent-task-1"))).thenReturn(2L);

        assertThatThrownBy(() -> inbox.admit(association(), notification(1), "digest-1", "{}"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Out-of-order A2A notification");
    }

    private static PostgresA2aNotificationInbox inbox(JdbcTemplate jdbc) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        return new PostgresA2aNotificationInbox(jdbc, transactions);
    }

    private static Object row(String contextId, String state, String digest, String status) throws Exception {
        Class<?> rowType = Class.forName(PostgresA2aNotificationInbox.class.getName() + "$Row");
        Constructor<?> constructor = rowType.getDeclaredConstructor(
                String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(contextId, state, digest, status);
    }

    private static A2aTaskAssociationStore.Association association() {
        return new A2aTaskAssociationStore.Association(
                "delegation-1", "task-1", "attempt-1", "workflow-1", "run-1", "a".repeat(40),
                "message-1", "developer", "b".repeat(64), "agent-task-1", "context-1");
    }

    private static A2aContracts.Notification notification(long sequence) {
        return new A2aContracts.Notification("developer", "agent-task-1", "context-1", sequence,
                A2aContracts.TaskState.COMPLETED, Instant.parse("2026-09-08T09:40:49Z"), List.of(), Map.of());
    }
}
