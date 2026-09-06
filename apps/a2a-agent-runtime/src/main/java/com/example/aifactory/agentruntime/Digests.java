package com.example.aifactory.agentruntime;

import java.security.MessageDigest;
import java.util.HexFormat;

final class Digests {
    private Digests() {}

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
