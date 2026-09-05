package com.weavelay.service.web.dto;

import java.util.List;

public class WeavePageDto {

    private String streamName;
    private int pageNumber;
    private String imageBase64;
    private int width;
    private int height;
    private List<OcrBoxDto> ocrRows;

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public List<OcrBoxDto> getOcrRows() {
        return ocrRows;
    }

    public void setOcrRows(List<OcrBoxDto> ocrRows) {
        this.ocrRows = ocrRows;
    }
}
