package com.weavelay.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 织版输出: streamName + feature + 页内序号.
 */
public final class WeaveRow {

    private final String streamName;
    private final String feature;
    private final int seq;

    public WeaveRow(String streamName, String feature, int seq) {
        this.streamName = streamName;
        this.feature = feature;
        this.seq = seq;
    }

    public List<Object> toList() {
        List<Object> row = new ArrayList<>(3);
        row.add(streamName);
        row.add(feature);
        row.add(seq);
        return row;
    }

    public String getStreamName() {
        return streamName;
    }

    public String getFeature() {
        return feature;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public String toString() {
        return streamName + " #" + seq + " " + feature;
    }
}
