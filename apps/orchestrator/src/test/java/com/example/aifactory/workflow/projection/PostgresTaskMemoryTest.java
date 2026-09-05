package com.example.aifactory.workflow.projection;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.workflow.EvidenceRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTaskMemoryTest {
    private JdbcTemplate jdbc;
    private FakeEvidenceRepository evidence;
    private PostgresTaskMemory memory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:task-memory-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE tasks (
                  task_id varchar(64) PRIMARY KEY,
                  repository_id varchar(63) NOT NULL,
                  current_attempt_id varchar(128) NOT NULL,
                  source_commit char(40) NOT NULL,
                  requirement_digest char(64) NOT NULL,
                  status varchar(48) NOT NULL,
                  created_at timestamp with time zone NOT NULL,
                  updated_at timestamp with time zone NOT NULL,
                  version bigint NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE task_projection_snapshots (
                  task_id varchar(64) PRIMARY KEY REFERENCES tasks(task_id),
                  attempt_id varchar(128) NOT NULL,
                  snapshot_uri varchar(1024) NOT NULL,
                  snapshot_digest char(64) NOT NULL,
                  projected_at timestamp with time zone NOT NULL,
                  version bigint NOT NULL DEFAULT 0,
                  UNIQUE (task_id, attempt_id, snapshot_uri, snapshot_digest)
                )
                """);
        evidence = new FakeEvidenceRepository();
        memory = new PostgresTaskMemory(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                evidence, new ObjectMapper());
    }

    @Test
    void persistsAndRestoresTheCompleteTaskProjection() {
        TaskState task = task("task-1");
        task.sourceCommit = "a".repeat(40);
        task.workspace = "/workspace/task-1";
        task.transition(TaskStatus.PLANNING, "Plan created");
        task.bindExecution("PIPELINE", "run-1", "pipeline-v1", 20_000, 50_000, 12);
        task.recordDelegation("developer-1", null, "developer", List.of(), "COMPLETED", null,
                120, 2, 300, 450, List.of("context.list_tree"));
        task.recordArtifact("plan", "plan", "COMPLETE", "INTERNAL",
                "evidence://task-1/pipeline-1/plan/" + "b".repeat(64), "b".repeat(64), 42, true);

        memory.save(task);
        TaskState restored = memory.find("task-1").orElseThrow();

        assertThat(restored).isNotSameAs(task);
        assertThat(restored.view()).usingRecursiveComparison().isEqualTo(task.view());
        assertThat(restored.workflowAttemptId).isEqualTo("pipeline-1");
        assertThat(restored.projectionVersion).isZero();
        assertThat(memory.list()).extracting(value -> value.id).containsExactly("task-1");
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = 'task-1'", String.class))
                .isEqualTo("PLANNING");
    }

    @Test
    void rejectsAStaleProjectionWriter() {
        TaskState current = task("task-lock");
        memory.save(current);
        TaskState stale = memory.find(current.id).orElseThrow();

        current.transition(TaskStatus.CLONING, "Clone started");
        memory.save(current);
        stale.transition(TaskStatus.PLANNING, "Stale plan");

        assertThatThrownBy(() -> memory.save(stale))
                .isInstanceOf(PostgresTaskMemory.OptimisticProjectionLockException.class);
        assertThat(memory.find(current.id).orElseThrow().status).isEqualTo(TaskStatus.CLONING);
    }

    @Test
    void refusesAProjectionWhoseEvidenceContentWasAltered() {
        TaskState task = task("task-tampered");
        memory.save(task);
        evidence.tamperReads = true;

        assertThatThrownBy(() -> memory.find(task.id))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("failed Evidence verification");
    }

    private static TaskState task(String id) {
        return new TaskState(id, "AF-0001",
                new TaskRequest("https://gitea.example/aiadmin/customer-api.git", "main",
                        "Implement durable task projection", null));
    }

    private static final class FakeEvidenceRepository implements EvidenceRepository {
        private final Map<String, StoredEvidence> metadata = new HashMap<>();
        private final Map<String, byte[]> content = new HashMap<>();
        private boolean tamperReads;

        @Override
        public StoredEvidence store(StoreRequest request) {
            String digest = sha256(request.content());
            assertThat(request.digest()).isEqualTo(digest);
            String uri = "evidence://" + request.taskId() + "/" + request.attemptId()
                    + "/metadata/" + digest;
            StoredEvidence stored = new StoredEvidence(uri, digest, "COMPLETE", request.mediaType(),
                    request.content().length, "CONFIDENTIAL", Instant.now().plusSeconds(3_600), Instant.now());
            metadata.put(uri, stored);
            content.put(uri, request.content());
            return stored;
        }

        @Override
        public RawEvidence read(ReadRequest request) {
            StoredEvidence stored = metadata.get(request.uri());
            byte[] bytes = content.get(request.uri()).clone();
            if (tamperReads) bytes = "altered".getBytes(StandardCharsets.UTF_8);
            return new RawEvidence(stored.uri(), "metadata", stored.digest(), stored.status(),
                    stored.classification(), bytes);
        }

        @Override public StoredManifest createManifest(ManifestRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            throw new UnsupportedOperationException();
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
