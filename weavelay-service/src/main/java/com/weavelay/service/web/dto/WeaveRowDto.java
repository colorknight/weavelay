package com.weavelay.service.web.dto;

public class WeaveRowDto {

    private String streamName;
    private String feature;
    private int seq;

    public WeaveRowDto() {
    }

    public WeaveRowDto(String streamName, String feature, int seq) {
        this.streamName = streamName;
        this.feature = feature;
        this.seq = seq;
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

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }
}
