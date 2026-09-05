package com.weavelay.core.page;

import com.weavelay.core.model.MergeParams;

/**
 * 单页型合并与配对参数.
 */
public final class PageRuleConfig {

    private final double yThreshold;
    private final double xThreshold;
    private final String mergeSep;
    private final PairMode pairMode;
    private final double rowTolerance;
    private final double colTolerance;

    public PageRuleConfig(
            double yThreshold,
            double xThreshold,
            String mergeSep,
            PairMode pairMode,
            double rowTolerance,
            double colTolerance) {
        this.yThreshold = yThreshold;
        this.xThreshold = xThreshold;
        this.mergeSep = mergeSep == null ? "" : mergeSep;
        this.pairMode = pairMode == null ? PairMode.NONE : pairMode;
        this.rowTolerance = rowTolerance;
        this.colTolerance = colTolerance;
    }

    public MergeParams toMergeParams() {
        MergeParams params = new MergeParams();
        params.setYThreshold(yThreshold);
        params.setXThreshold(xThreshold);
        params.setMergeSep(mergeSep);
        params.setByPage(true);
        return params;
    }

    public double getYThreshold() {
        return yThreshold;
    }

    public double getXThreshold() {
        return xThreshold;
    }

    public String getMergeSep() {
        return mergeSep;
    }

    public PairMode getPairMode() {
        return pairMode;
    }

    public double getRowTolerance() {
        return rowTolerance;
    }

    public double getColTolerance() {
        return colTolerance;
    }
}
