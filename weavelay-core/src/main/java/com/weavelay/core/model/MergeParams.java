package com.weavelay.core.model;

/**
 * 合并参数, 对应原 Python merge 脚本 params.
 */
public final class MergeParams {

    private double yThreshold = 80;
    private double xThreshold = 350;
    private String mergeSep = "";
    private boolean byPage = true;

    public double getYThreshold() {
        return yThreshold;
    }

    public void setYThreshold(double yThreshold) {
        this.yThreshold = yThreshold;
    }

    public double getXThreshold() {
        return xThreshold;
    }

    public void setXThreshold(double xThreshold) {
        this.xThreshold = xThreshold;
    }

    public String getMergeSep() {
        return mergeSep;
    }

    public void setMergeSep(String mergeSep) {
        this.mergeSep = mergeSep == null ? "" : mergeSep;
    }

    public boolean isByPage() {
        return byPage;
    }

    public void setByPage(boolean byPage) {
        this.byPage = byPage;
    }
}
