package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

@Component
public class ProcessRunner {
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    public String run(List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) pb.directory(directory.toFile());
        Process p = pb.start();
        Future<byte[]> output = ioPool.submit(() -> p.getInputStream().readAllBytes());
        boolean done = p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timeout: " + commandForError(command));
        }
        String out;
        try {
            out = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot collect command output", e);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("Command failed (" + p.exitValue() + "): " + commandForError(command) + "\n" + out);
        }
        return out;
    }

    static String commandForError(List<String> command) {
        return command.stream()
                .map(arg -> arg.startsWith("SONAR_TOKEN=") ? "SONAR_TOKEN=[REDACTED]" : arg)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }
}
