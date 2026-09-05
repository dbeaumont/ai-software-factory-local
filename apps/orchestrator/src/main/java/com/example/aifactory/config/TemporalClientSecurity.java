package com.example.aifactory.config;

import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Builds Temporal transport security exclusively from mounted files. */
final class TemporalClientSecurity {
    private static final long MAX_SECRET_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> FORBIDDEN_SECRET_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private TemporalClientSecurity() {}

    static WorkflowServiceStubsOptions build(TemporalProperties properties) {
        WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target());
        TemporalProperties.Security security = properties.security();
        if (!security.tlsEnabled()) return builder.build();

        builder.setEnableHttps(true);
        builder.setChannelInitializer(channel -> channel.overrideAuthority(security.serverName()));
        try {
            if (!security.clientCertificatePath().isEmpty()) {
                Path certificate = publicFile(security.clientCertificatePath(), "client certificate");
                Path privateKey = secretFile(security.privateKeyPath(), "private key");
                try (InputStream certificateInput = Files.newInputStream(certificate);
                     InputStream keyInput = Files.newInputStream(privateKey)) {
                    builder.setSslContext(SimpleSslContextBuilder.forPKCS8(certificateInput, keyInput).build());
                }
            } else {
                builder.setSslContext(SimpleSslContextBuilder.noKeyOrCertChain().build());
            }
            if (!security.apiKeyFile().isEmpty()) {
                String apiKey = readSecret(secretFile(security.apiKeyFile(), "API key"));
                builder.addApiKey(() -> apiKey);
            }
            return builder.build();
        } catch (Exception exception) {
            throw new IllegalStateException("Temporal TLS credentials cannot be loaded", exception);
        }
    }

    static Path secretFile(String value, String label) throws Exception {
        Path path = publicFile(value, label);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(FORBIDDEN_SECRET_PERMISSIONS::contains)) {
                throw new SecurityException("Temporal " + label + " must not be accessible by group or others");
            }
        }
        return path;
    }

    private static Path publicFile(String value, String label) throws Exception {
        Path path = Path.of(value);
        if (!path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)
                || Files.size(path) < 1 || Files.size(path) > MAX_SECRET_BYTES) {
            throw new SecurityException("Temporal " + label + " file is invalid");
        }
        return path;
    }

    private static String readSecret(Path path) throws Exception {
        String secret = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (secret.isEmpty() || secret.length() > 16_384 || secret.chars().anyMatch(Character::isWhitespace)) {
            throw new SecurityException("Temporal API key file has invalid content");
        }
        return secret;
    }
}
