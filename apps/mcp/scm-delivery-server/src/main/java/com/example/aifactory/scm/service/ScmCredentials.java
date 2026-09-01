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
        return read(properties.giteaTokenFile(), "GITEA_TOKEN", "SCM credential");
    }

    public byte[] approvalKey() {
        return read(properties.approvalKeyFile(), "APPROVAL_ATTESTATION_KEY", "approval attestation key")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String read(java.nio.file.Path path, String key, String label) {
        try {
            String content = Files.readString(path);
            String value = content.lines()
                    .filter(line -> line.startsWith(key + "="))
                    .map(line -> line.substring(key.length() + 1).strip())
                    .findFirst()
                    .orElseGet(content::strip);
            if (value.length() < 16 || value.contains("\n") || value.contains("\r")) {
                throw new IllegalStateException(label + " file is invalid");
            }
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException(label + " is unavailable");
        }
    }
}
