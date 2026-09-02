package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, conservative conflict detection across validated patch artifacts. */
@Component
public final class PatchConflictDetector {
    private static final Pattern DIFF_HEADER = Pattern.compile("diff --git a/([^\\s]+) b/([^\\s]+)");
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    public Report detect(List<Candidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            throw new IllegalArgumentException("At least two validated patches are required");
        }
        Set<String> ids = new LinkedHashSet<>();
        candidates.forEach(candidate -> {
            if (candidate == null || candidate.proposalId() == null || candidate.patch() == null
                    || !ids.add(candidate.proposalId())) {
                throw new IllegalArgumentException("Patch candidate identity is missing or duplicated");
            }
        });
        List<Conflict> conflicts = new ArrayList<>();
        for (int left = 0; left < candidates.size(); left++) {
            for (int right = left + 1; right < candidates.size(); right++) {
                compare(candidates.get(left), candidates.get(right), conflicts);
            }
        }
        List<Conflict> ordered = conflicts.stream().distinct().sorted(Comparator
                .comparing((Conflict conflict) -> conflict.type().name())
                .thenComparing(Conflict::path)
                .thenComparing(Conflict::leftProposalId)
                .thenComparing(Conflict::rightProposalId)).toList();
        return new Report(ordered.isEmpty(), ordered);
    }

    private static void compare(Candidate left, Candidate right, List<Conflict> conflicts) {
        for (PatchProposalValidator.FileChange leftChange : left.patch().changes()) {
            for (PatchProposalValidator.FileChange rightChange : right.patch().changes()) {
                Set<String> common = paths(leftChange);
                common.retainAll(paths(rightChange));
                common.forEach(path -> conflicts.add(conflict(
                        Type.COMMON_FILE, path, left, right, "Both patches reference the same file.")));
                detectRename(left, right, leftChange, rightChange, conflicts);
                detectDelete(left, right, leftChange, rightChange, conflicts);
            }
        }
        for (Hunk leftHunk : hunks(left.patch().content())) {
            for (Hunk rightHunk : hunks(right.patch().content())) {
                if (leftHunk.path().equals(rightHunk.path()) && overlaps(leftHunk, rightHunk)) {
                    conflicts.add(conflict(Type.INCOMPATIBLE_HUNK, leftHunk.path(), left, right,
                            "Old-line ranges overlap: " + leftHunk.range() + " / " + rightHunk.range()));
                }
            }
        }
    }

    private static void detectRename(Candidate left, Candidate right,
                                     PatchProposalValidator.FileChange first,
                                     PatchProposalValidator.FileChange second, List<Conflict> conflicts) {
        if ("RENAME".equals(first.operation()) && "RENAME".equals(second.operation())
                && (same(first.previousPath(), second.previousPath()) && !first.path().equals(second.path())
                || first.path().equals(second.path()) && !same(first.previousPath(), second.previousPath()))) {
            conflicts.add(conflict(Type.RENAME_COLLISION, first.path(), left, right,
                    "Rename sources or targets diverge."));
        }
    }

    private static void detectDelete(Candidate left, Candidate right,
                                     PatchProposalValidator.FileChange first,
                                     PatchProposalValidator.FileChange second, List<Conflict> conflicts) {
        if ("DELETE".equals(first.operation()) && paths(second).contains(first.path())
                || "DELETE".equals(second.operation()) && paths(first).contains(second.path())) {
            String path = "DELETE".equals(first.operation()) ? first.path() : second.path();
            conflicts.add(conflict(Type.DELETE_COLLISION, path, left, right,
                    "A deleted file is also changed by another patch."));
        }
    }

    private static Set<String> paths(PatchProposalValidator.FileChange change) {
        Set<String> paths = new LinkedHashSet<>();
        paths.add(change.path());
        if (change.previousPath() != null) paths.add(change.previousPath());
        return paths;
    }

    private static List<Hunk> hunks(String patch) {
        String currentPath = null;
        List<Hunk> hunks = new ArrayList<>();
        for (String line : patch.split("\\n")) {
            Matcher diff = DIFF_HEADER.matcher(line);
            if (diff.matches()) {
                currentPath = diff.group(2);
                continue;
            }
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (currentPath != null && hunk.matches()) {
                int start = Integer.parseInt(hunk.group(1));
                int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                hunks.add(new Hunk(currentPath, start, count));
            }
        }
        return List.copyOf(hunks);
    }

    private static boolean overlaps(Hunk left, Hunk right) {
        if (left.oldCount() == 0 || right.oldCount() == 0) {
            return left.oldStart() == right.oldStart()
                    || left.oldStart() >= right.oldStart() && left.oldStart() <= right.oldEnd() + 1
                    || right.oldStart() >= left.oldStart() && right.oldStart() <= left.oldEnd() + 1;
        }
        return left.oldStart() <= right.oldEnd() && right.oldStart() <= left.oldEnd();
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Conflict conflict(Type type, String path, Candidate left, Candidate right, String reason) {
        String first = left.proposalId().compareTo(right.proposalId()) <= 0
                ? left.proposalId() : right.proposalId();
        String second = first.equals(left.proposalId()) ? right.proposalId() : left.proposalId();
        return new Conflict(type, path, first, second, reason);
    }

    public record Candidate(String proposalId, PatchProposalValidator.ValidatedPatch patch) {}

    public record Conflict(Type type, String path, String leftProposalId, String rightProposalId, String reason) {}

    public record Report(boolean safeToIntegrate, List<Conflict> conflicts) {
        public Report {
            conflicts = List.copyOf(conflicts);
        }
    }

    private record Hunk(String path, int oldStart, int oldCount) {
        int oldEnd() {
            return oldCount == 0 ? oldStart : oldStart + oldCount - 1;
        }

        String range() {
            return oldStart + "," + oldCount;
        }
    }

    public enum Type { COMMON_FILE, INCOMPATIBLE_HUNK, RENAME_COLLISION, DELETE_COLLISION }
}
