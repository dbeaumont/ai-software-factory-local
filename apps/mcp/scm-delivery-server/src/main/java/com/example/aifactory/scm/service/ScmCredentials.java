package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;

@Service
public class ScmCredentials {
    private final ScmDeliveryProperties properties;

    public ScmCredentials(ScmDeliveryProperties properties) {
        this.properties = properties;
    }

    public String giteaToken() {
        return read(properties.giteaTokenFile(), "SCM credential");
    }

    public byte[] approvalKey() {
        return read(properties.approvalKeyFile(), "approval attestation key").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String read(java.nio.file.Path path, String label) {
        try {
            String value = Files.readString(path).strip();
            if (value.length() < 16 || value.contains("\n") || value.contains("\r")) {
                throw new IllegalStateException(label + " file is invalid");
            }
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException(label + " is unavailable");
        }
    }
}
