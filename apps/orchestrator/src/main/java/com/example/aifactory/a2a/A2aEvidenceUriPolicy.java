package com.example.aifactory.a2a;

import java.net.URI;

/** Allows only immutable Evidence URIs bound to the expected task, attempt and digest. */
public final class A2aEvidenceUriPolicy {
    private A2aEvidenceUriPolicy() {}

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
        if (!"evidence".equals(uri.getScheme()) || !taskId.equals(uri.getHost()) || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().startsWith(prefix)
                || !uri.getPath().endsWith("/" + digest) || uri.getPath().contains("//")
                || uri.getPath().contains("/../") || uri.getPath().contains("/./")) {
            throw new SecurityException("Evidence reference is outside the allowed namespace");
        }
        return uri;
    }
}
