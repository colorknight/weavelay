package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PpOcrV5ResultParser {

    private static final Pattern TEXT_BOX = Pattern.compile(
            "TextBox\\[(\\d+)\\].*?score\\(([^)]+)\\).*?"
                    + "\\[x: (\\d+), y: (\\d+)\\], \\[x: (\\d+), y: (\\d+)\\], "
                    + "\\[x: (\\d+), y: (\\d+)\\], \\[x: (\\d+), y: (\\d+)\\]");
    private static final Pattern TEXT_LINE = Pattern.compile("textLine\\[(\\d+)\\]\\((.*)\\)\\s*$");

    private PpOcrV5ResultParser() {
    }

    static List<OcrRow> parse(Path resultTxt, String streamName, float minBoxScore) throws IOException {
        return parse(resultTxt, streamName, minBoxScore, 0);
    }

    static List<OcrRow> parse(Path resultTxt, String streamName, float minBoxScore, int padding)
            throws IOException {
        if (resultTxt == null || !Files.isRegularFile(resultTxt)) {
            return List.of();
        }
        String content = Files.readString(resultTxt, StandardCharsets.UTF_8);
        Map<Integer, Box> boxes = new HashMap<Integer, Box>();
        Map<Integer, String> lines = new HashMap<Integer, String>();

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            Matcher boxMatcher = TEXT_BOX.matcher(line);
            if (boxMatcher.find()) {
                int index = Integer.parseInt(boxMatcher.group(1));
                float score = Float.parseFloat(boxMatcher.group(2));
                int[] xs = {
                        Integer.parseInt(boxMatcher.group(3)),
                        Integer.parseInt(boxMatcher.group(5)),
                        Integer.parseInt(boxMatcher.group(7)),
                        Integer.parseInt(boxMatcher.group(9))
                };
                int[] ys = {
                        Integer.parseInt(boxMatcher.group(4)),
                        Integer.parseInt(boxMatcher.group(6)),
                        Integer.parseInt(boxMatcher.group(8)),
                        Integer.parseInt(boxMatcher.group(10))
                };
                boxes.put(index, new Box(
                        score,
                        toOriginalCoord(min(xs), padding),
                        toOriginalCoord(min(ys), padding),
                        toOriginalCoord(max(xs), padding),
                        toOriginalCoord(max(ys), padding)));
                continue;
            }
            Matcher lineMatcher = TEXT_LINE.matcher(line);
            if (lineMatcher.find()) {
                lines.put(Integer.parseInt(lineMatcher.group(1)), lineMatcher.group(2));
            }
        }

        List<OcrRow> rows = new ArrayList<OcrRow>();
        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            String text = entry.getValue();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            Box box = boxes.get(entry.getKey());
            if (box == null) {
                continue;
            }
            if (minBoxScore > 0f && box.score < minBoxScore) {
                continue;
            }
            rows.add(new OcrRow(
                    streamName,
                    text.trim(),
                    box.score,
                    box.minX,
                    box.minY,
                    box.maxX,
                    box.maxY,
                    null));
        }
        return rows;
    }

    private static int toOriginalCoord(int value, int padding) {
        if (padding <= 0) {
            return value;
        }
        return Math.max(0, value - padding);
    }

    private static int min(int[] values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            min = Math.min(min, value);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int max(int[] values) {
        int max = Integer.MIN_VALUE;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    private static final class Box {
        private final float score;
        private final int minX;
        private final int minY;
        private final int maxX;
        private final int maxY;

        private Box(float score, int minX, int minY, int maxX, int maxY) {
            this.score = score;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }
}
