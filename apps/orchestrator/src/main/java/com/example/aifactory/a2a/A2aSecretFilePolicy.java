package com.example.aifactory.a2a;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/** Fail-closed reader for orchestrator-side A2A secrets. */
final class A2aSecretFilePolicy {
    private static final Set<PosixFilePermission> FORBIDDEN = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
            PosixFilePermission.OWNER_EXECUTE);

    private A2aSecretFilePolicy() {}

    static byte[] read(Path path, int maximumBytes) {
        try {
            if (path == null || maximumBytes < 1 || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("A2A secret mount is unavailable");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(FORBIDDEN::contains)) {
                throw new SecurityException("A2A secret mount permissions are unsafe");
            }
            long size = Files.size(path);
            if (size < 1 || size > maximumBytes) throw new SecurityException("A2A secret mount size is invalid");
            byte[] value = Files.readAllBytes(path);
            if (value.length < 1 || value.length > maximumBytes) {
                Arrays.fill(value, (byte) 0);
                throw new SecurityException("A2A secret mount size changed while reading");
            }
            return value;
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("A2A secret mount is unavailable", failure);
        }
    }
}
