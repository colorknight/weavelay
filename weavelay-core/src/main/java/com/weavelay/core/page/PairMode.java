package com.weavelay.core.page;

public enum PairMode {

    HORIZONTAL,
    VERTICAL,
    NONE;

    public static PairMode fromCode(String code) {
        if (code == null) {
            return NONE;
        }
        try {
            return PairMode.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
