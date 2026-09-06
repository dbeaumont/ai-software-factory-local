package com.example.aifactory.agentcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretFilePolicyTest {
    @TempDir Path temporary;

    @Test
    void acceptsOnlyBoundedOwnerReadableRegularFiles() throws Exception {
        Path secure = temporary.resolve("secure");
        Files.writeString(secure, "rotatable-secret");
        Files.setPosixFilePermissions(secure, PosixFilePermissions.fromString("rw-------"));
        assertArrayEquals("rotatable-secret".getBytes(), SecretFilePolicy.read(secure, 64));

        Files.setPosixFilePermissions(secure, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(SecurityException.class, () -> SecretFilePolicy.read(secure, 64));
        Files.setPosixFilePermissions(secure, PosixFilePermissions.fromString("rw-------"));
        assertThrows(SecurityException.class, () -> SecretFilePolicy.read(secure, 4));

        Path link = temporary.resolve("link");
        Files.createSymbolicLink(link, secure.getFileName());
        assertThrows(SecurityException.class, () -> SecretFilePolicy.read(link, 64));
    }
}
