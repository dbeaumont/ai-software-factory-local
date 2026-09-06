package com.example.aifactory.agentcore;

import java.net.URI;

/** Strictly binds an opaque Evidence reference to the expected task, attempt and SHA-256 digest. */
public final class EvidenceUriPolicy {
    private EvidenceUriPolicy() {}

    public static URI requireBound(String raw, String taskId, String attemptId, String digest) {
        if (raw == null || taskId == null || attemptId == null || digest == null
                || !taskId.matches("[A-Za-z0-9_-]{1,128}")
                || !attemptId.matches("[A-Za-z0-9_-]{1,128}") || !digest.matches("[0-9a-f]{64}")) {
            throw new SecurityException("Evidence reference binding is invalid");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (RuntimeException invalid) {
            throw new SecurityException("Evidence reference is invalid", invalid);
        }
        String prefix = "/" + attemptId + "/";
        String suffix = "/" + digest;
        if (!"evidence".equals(uri.getScheme()) || !taskId.equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPath() == null || !uri.getPath().startsWith(prefix)
                || !uri.getPath().endsWith(suffix) || uri.getPath().contains("//")
                || uri.getPath().contains("/../") || uri.getPath().contains("/./")) {
            throw new SecurityException("Evidence reference is outside the allowed namespace");
        }
        return uri;
    }
}
