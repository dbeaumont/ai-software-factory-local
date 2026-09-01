package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

@Service
public class ScmWorktreeStager {
    private final Path stagingRoot;

    public ScmWorktreeStager(ScmDeliveryProperties properties) throws IOException {
        this.stagingRoot = properties.stateRoot().resolve("worktrees").normalize();
        Files.createDirectories(stagingRoot);
    }

    public Path stage(Path source, String taskId, String attemptId) throws IOException {
        Path target = stagingRoot.resolve(taskId + "-" + attemptId).normalize();
        if (!target.startsWith(stagingRoot)) {
            throw new SecurityException("invalid SCM staging path");
        }
        delete(target);
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new SecurityException("symbolic links are forbidden in SCM workspaces");
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new SecurityException("unsupported file type in SCM workspace");
                }
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    public void delete(Path target) throws IOException {
        if (target == null || !target.normalize().startsWith(stagingRoot) || !Files.exists(target)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
