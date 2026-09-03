package com.example.aifactory.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repairs one common model formatting error using the immutable workspace as the source of truth. */
final class WorkspaceDiffNormalizer {
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$");

    private WorkspaceDiffNormalizer() {
    }

    static String normalize(Path workspace, String patch) throws IOException {
        List<String> lines = new ArrayList<>(List.of(patch.split("\\n", -1)));
        Path currentFile = null;
        for (int index = 0; index < lines.size(); index++) {
            Matcher file = NEW_FILE.matcher(lines.get(index));
            if (file.matches()) {
                currentFile = workspace.resolve(file.group(1)).normalize();
                if (!currentFile.startsWith(workspace) || !Files.isRegularFile(currentFile)) {
                    currentFile = null;
                }
                continue;
            }
            if (!lines.get(index).startsWith("@@ ") || currentFile == null) {
                continue;
            }
            int end = index + 1;
            while (end < lines.size() && !lines.get(end).startsWith("@@ ")
                    && !lines.get(end).startsWith("diff --git ")) {
                end++;
            }
            repairBlankContext(lines, index + 1, end, Files.readAllLines(currentFile));
            index = end - 1;
        }
        return UnifiedDiffNormalizer.normalize(String.join("\n", lines));
    }

    private static void repairBlankContext(List<String> patch, int start, int end, List<String> source) {
        while (!contains(source, oldSide(patch, start, end))) {
            int candidate = blankContextFollowedByAddition(patch, start, end);
            if (candidate < 0) {
                return;
            }
            patch.set(candidate, "+");
            if (!contains(source, oldSide(patch, start, end))) {
                patch.set(candidate, " ");
                return;
            }
        }
    }

    private static int blankContextFollowedByAddition(List<String> patch, int start, int end) {
        for (int index = start; index + 1 < end; index++) {
            if (" ".equals(patch.get(index)) && patch.get(index + 1).startsWith("+")) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> oldSide(List<String> patch, int start, int end) {
        List<String> old = new ArrayList<>();
        for (int index = start; index < end; index++) {
            String line = patch.get(index);
            if (line.startsWith(" ") || line.startsWith("-")) {
                old.add(line.substring(1));
            }
        }
        return old;
    }

    private static boolean contains(List<String> source, List<String> expected) {
        if (expected.isEmpty() || expected.size() > source.size()) {
            return false;
        }
        for (int start = 0; start <= source.size() - expected.size(); start++) {
            if (source.subList(start, start + expected.size()).equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
