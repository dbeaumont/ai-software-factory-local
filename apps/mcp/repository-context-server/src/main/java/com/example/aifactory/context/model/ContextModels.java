package com.example.aifactory.context.model;

import java.time.Instant;
import java.util.List;

public final class ContextModels {
    private ContextModels() {
    }

    public record RequestContext(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline) {
    }

    public record ListTreeRequest(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline,
            String path,
            Integer depth,
            Integer maxEntries,
            List<String> include,
            List<String> exclude,
            String cursor) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline);
        }

        public ListTreeRequest(String schemaVersion, String taskId, String sourceCommit, String actor,
                               String traceId, String path, Integer depth, Integer maxEntries) {
            this(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                    ContextModels.traceparent(traceId), ContextModels.deadline(), path, depth, maxEntries,
                    null, null, null);
        }
    }

    public record TreeEntry(String path, String type, long size) {
    }

    public record ListTreeResult(String sourceCommit, List<TreeEntry> entries, boolean truncated, String nextCursor) {
    }

    public record ReadFileRequest(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline,
            String path,
            Integer startLine,
            Integer endLine,
            Integer maxBytes) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline);
        }

        public ReadFileRequest(String schemaVersion, String taskId, String sourceCommit, String actor, String traceId,
                               String path, Integer startLine, Integer endLine, Integer maxBytes) {
            this(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                    ContextModels.traceparent(traceId), ContextModels.deadline(), path, startLine, endLine, maxBytes);
        }
    }

    public record ReadFileResult(
            String path,
            int startLine,
            int endLine,
            String content,
            String mimeType,
            String sha256,
            boolean truncated) {
    }

    public record SearchCodeRequest(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline,
            String query,
            String path,
            Integer maxResults) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline);
        }

        public SearchCodeRequest(String schemaVersion, String taskId, String sourceCommit, String actor, String traceId,
                                 String query, String path, Integer maxResults) {
            this(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                    ContextModels.traceparent(traceId), ContextModels.deadline(), query, path, maxResults);
        }
    }

    public record SearchMatch(String path, int line, String excerpt) {
    }

    public record SearchCodeResult(String sourceCommit, List<SearchMatch> matches, boolean truncated) {
    }

    public record RepositoryRulesRequest(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline);
        }

        public RepositoryRulesRequest(String schemaVersion, String taskId, String sourceCommit, String actor,
                                      String traceId) {
            this(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                    ContextModels.traceparent(traceId), ContextModels.deadline());
        }
    }

    public record RepositoryRule(ReadFileResult document, int applicabilityOrder, String provenance) {
    }

    public record RepositoryRulesResult(String sourceCommit, List<RepositoryRule> rules) {
    }

    private static String traceparent(String traceId) {
        return "00-" + traceId + "-0123456789abcdef-01";
    }

    private static String deadline() {
        return Instant.now().plusSeconds(60).toString();
    }
}
