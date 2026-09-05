package com.weavelay.service.web.dto;

import java.util.List;

public class WeaveSessionDto {

    private List<WeavePageDto> pages;
    private List<WeaveRowDto> weaveRows;

    public List<WeavePageDto> getPages() {
        return pages;
    }

    public void setPages(List<WeavePageDto> pages) {
        this.pages = pages;
    }

    public List<WeaveRowDto> getWeaveRows() {
        return weaveRows;
    }

    public void setWeaveRows(List<WeaveRowDto> weaveRows) {
        this.weaveRows = weaveRows;
    }
}
