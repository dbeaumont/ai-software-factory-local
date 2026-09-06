package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aSecretFilePolicyTest {
    @TempDir Path temporary;

    @Test
    void rejectsLoosePermissionsAndSymbolicLinks() throws Exception {
        Path secret = temporary.resolve("secret");
        Files.writeString(secret, "a".repeat(32));
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        assertThat(A2aSecretFilePolicy.read(secret, 64)).hasSize(32);

        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> A2aSecretFilePolicy.read(secret, 64)).isInstanceOf(SecurityException.class);
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        Path link = temporary.resolve("secret-link");
        Files.createSymbolicLink(link, secret.getFileName());
        assertThatThrownBy(() -> A2aSecretFilePolicy.read(link, 64)).isInstanceOf(SecurityException.class);
    }
}
