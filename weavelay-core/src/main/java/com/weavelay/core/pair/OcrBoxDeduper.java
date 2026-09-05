package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 去掉被更大检测框完全包住且文本为其子串的冗余框 (常见于 merge 前的大框 + 行内小框).
 */
public final class OcrBoxDeduper {

    private static final double BBOX_MARGIN = 2.0;

    private OcrBoxDeduper() {
    }

    public static List<OcrRow> dropStrictlyContained(List<OcrRow> rows) {
        if (rows == null || rows.size() <= 1) {
            return rows == null ? List.of() : rows;
        }
        List<OcrRow> keep = new ArrayList<OcrRow>();
        for (OcrRow candidate : rows) {
            if (candidate == null) {
                continue;
            }
            boolean contained = false;
            for (OcrRow other : rows) {
                if (other == null || other == candidate) {
                    continue;
                }
                if (bboxContains(other, candidate) && textContains(other.getFeature(), candidate.getFeature())) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                keep.add(candidate);
            }
        }
        return keep;
    }

    private static boolean bboxContains(OcrRow outer, OcrRow inner) {
        if (!outer.hasBbox() || !inner.hasBbox()) {
            return false;
        }
        return outer.getStartX() <= inner.getStartX() + BBOX_MARGIN
                && outer.getStartY() <= inner.getStartY() + BBOX_MARGIN
                && outer.getEndX() >= inner.getEndX() - BBOX_MARGIN
                && outer.getEndY() >= inner.getEndY() - BBOX_MARGIN;
    }

    private static boolean textContains(String outerText, String innerText) {
        if (innerText == null || innerText.trim().isEmpty()) {
            return true;
        }
        if (outerText == null) {
            return false;
        }
        String outer = normalize(outerText);
        String inner = normalize(innerText);
        if (inner.isEmpty()) {
            return true;
        }
        return outer.contains(inner);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }
}
