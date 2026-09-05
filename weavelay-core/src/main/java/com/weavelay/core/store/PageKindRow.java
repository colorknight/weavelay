package com.weavelay.core.store;

/**
 * page_kind 表行.
 */
final class PageKindRow {

    final String code;
    final String displayName;
    final int classifyPriority;
    final double yThreshold;
    final double xThreshold;
    final String mergeSep;
    final String pairMode;
    final double rowTolerance;
    final double colTolerance;

    PageKindRow(
            String code,
            String displayName,
            int classifyPriority,
            double yThreshold,
            double xThreshold,
            String mergeSep,
            String pairMode,
            double rowTolerance,
            double colTolerance) {
        this.code = code;
        this.displayName = displayName;
        this.classifyPriority = classifyPriority;
        this.yThreshold = yThreshold;
        this.xThreshold = xThreshold;
        this.mergeSep = mergeSep;
        this.pairMode = pairMode;
        this.rowTolerance = rowTolerance;
        this.colTolerance = colTolerance;
    }
}
