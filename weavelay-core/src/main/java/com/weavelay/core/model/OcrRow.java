package com.weavelay.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OCR 检测行, 与算子列集一致.
 */
public final class OcrRow {

    private String streamName;
    private String feature;
    private double prob;
    private double startX;
    private double startY;
    private double endX;
    private double endY;
    private Object metas;

    public OcrRow() {
    }

    public OcrRow(
            String streamName,
            String feature,
            double prob,
            double startX,
            double startY,
            double endX,
            double endY,
            Object metas) {
        this.streamName = streamName;
        this.feature = feature;
        this.prob = prob;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.metas = metas;
    }

    public static OcrRow fromList(List<?> row) {
        if (row == null || row.size() < 2) {
            return null;
        }
        OcrRow o = new OcrRow();
        o.streamName = stringAt(row, 0);
        o.feature = stringAt(row, 1);
        o.prob = doubleAt(row, 2, 1.0);
        o.startX = doubleAt(row, 3, 0);
        o.startY = doubleAt(row, 4, 0);
        o.endX = doubleAt(row, 5, 0);
        o.endY = doubleAt(row, 6, 0);
        o.metas = row.size() > 7 ? row.get(7) : null;
        if (o.feature == null || o.feature.trim().isEmpty()) {
            return null;
        }
        return o;
    }

    public List<Object> toList() {
        List<Object> list = new ArrayList<>(8);
        list.add(streamName);
        list.add(feature);
        list.add(prob);
        list.add(startX);
        list.add(startY);
        list.add(endX);
        list.add(endY);
        list.add(metas);
        return list;
    }

    public double centerY() {
        return (startY + endY) / 2.0;
    }

    public double centerX() {
        return (startX + endX) / 2.0;
    }

    public boolean hasBbox() {
        return !(startX == 0 && startY == 0 && endX == 0 && endY == 0);
    }

    public OcrRow copy() {
        return new OcrRow(streamName, feature, prob, startX, startY, endX, endY, metas);
    }

    private static String stringAt(List<?> row, int idx) {
        if (row.size() <= idx || row.get(idx) == null) {
            return "";
        }
        return String.valueOf(row.get(idx));
    }

    private static double doubleAt(List<?> row, int idx, double defaultValue) {
        if (row.size() <= idx || row.get(idx) == null) {
            return defaultValue;
        }
        if (row.get(idx) instanceof Number) {
            return ((Number) row.get(idx)).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(row.get(idx)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public double getProb() {
        return prob;
    }

    public void setProb(double prob) {
        this.prob = prob;
    }

    public double getStartX() {
        return startX;
    }

    public void setStartX(double startX) {
        this.startX = startX;
    }

    public double getStartY() {
        return startY;
    }

    public void setStartY(double startY) {
        this.startY = startY;
    }

    public double getEndX() {
        return endX;
    }

    public void setEndX(double endX) {
        this.endX = endX;
    }

    public double getEndY() {
        return endY;
    }

    public void setEndY(double endY) {
        this.endY = endY;
    }

    public Object getMetas() {
        return metas;
    }

    public void setMetas(Object metas) {
        this.metas = metas;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OcrRow)) {
            return false;
        }
        OcrRow ocrRow = (OcrRow) o;
        return Double.compare(ocrRow.prob, prob) == 0
                && Double.compare(ocrRow.startX, startX) == 0
                && Double.compare(ocrRow.startY, startY) == 0
                && Double.compare(ocrRow.endX, endX) == 0
                && Double.compare(ocrRow.endY, endY) == 0
                && Objects.equals(streamName, ocrRow.streamName)
                && Objects.equals(feature, ocrRow.feature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamName, feature, prob, startX, startY, endX, endY);
    }
}
