package com.example.aifactory.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnifiedDiffNormalizer {
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)");

    private UnifiedDiffNormalizer() {
    }

    static String normalize(String patch) {
        String[] lines = patch.split("\\n", -1);
        List<String> result = new ArrayList<>(List.of(lines));

        for (int index = 0; index < result.size(); index++) {
            Matcher header = HUNK_HEADER.matcher(result.get(index));
            if (!header.matches()) continue;

            int oldLines = 0;
            int newLines = 0;
            int end = index + 1;
            while (end < result.size() && !result.get(end).startsWith("@@ ") && !result.get(end).startsWith("diff --git ")) {
                String line = result.get(end);
                if (line.startsWith(" ")) {
                    oldLines++;
                    newLines++;
                } else if (line.startsWith("-")) {
                    oldLines++;
                } else if (line.startsWith("+")) {
                    newLines++;
                }
                end++;
            }

            result.set(index, "@@ -" + range(header.group(1), oldLines) + " +" + range(header.group(3), newLines) + " @@" + header.group(5));
        }
        return String.join("\n", result).stripTrailing() + "\n";
    }

    private static String range(String start, int count) {
        return count == 1 ? start : start + "," + count;
    }
}
