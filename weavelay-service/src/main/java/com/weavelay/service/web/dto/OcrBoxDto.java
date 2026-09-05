package com.weavelay.service.web.dto;

import java.util.List;

public class OcrBoxDto {

    private String feature;
    private double prob;
    private double startX;
    private double startY;
    private double endX;
    private double endY;

    public OcrBoxDto() {
    }

    public OcrBoxDto(String feature, double prob, double startX, double startY, double endX, double endY) {
        this.feature = feature;
        this.prob = prob;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
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
}
