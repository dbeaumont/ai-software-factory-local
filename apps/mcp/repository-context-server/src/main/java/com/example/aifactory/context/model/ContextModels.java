package com.example.aifactory.context.model;

import java.util.List;

public final class ContextModels {
    private ContextModels() {
    }

    public record RequestContext(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId) {
    }

    public record ListTreeRequest(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId,
            String path,
            Integer depth,
            Integer maxEntries) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, sourceCommit, actor, traceId);
        }
    }

    public record TreeEntry(String path, String type, long size) {
    }

    public record ListTreeResult(String sourceCommit, List<TreeEntry> entries, boolean truncated) {
    }

    public record ReadFileRequest(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId,
            String path,
            Integer startLine,
            Integer endLine,
            Integer maxBytes) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, sourceCommit, actor, traceId);
        }
    }

    public record ReadFileResult(
            String path,
            int startLine,
            int endLine,
            String content,
            String sha256,
            boolean truncated) {
    }

    public record SearchCodeRequest(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId,
            String query,
            String path,
            Integer maxResults) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, sourceCommit, actor, traceId);
        }
    }

    public record SearchMatch(String path, int line, String excerpt) {
    }

    public record SearchCodeResult(String sourceCommit, List<SearchMatch> matches, boolean truncated) {
    }

    public record RepositoryRulesRequest(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId) {
        public RequestContext context() {
            return new RequestContext(schemaVersion, taskId, sourceCommit, actor, traceId);
        }
    }

    public record RepositoryRulesResult(String sourceCommit, List<ReadFileResult> rules) {
    }
}

