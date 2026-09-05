package com.weavelay.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 挂在 {@link com.weavelay.core.model.OcrRow#getMetas()} 上的字/词级结果。
 */
public final class OcrTokenMeta {

    public enum Level {
        CHAR,
        WORD
    }

    private final Level level;
    private final List<OcrTokenBox> tokens;

    public OcrTokenMeta(Level level, List<OcrTokenBox> tokens) {
        this.level = level == null ? Level.CHAR : level;
        if (tokens == null || tokens.isEmpty()) {
            this.tokens = Collections.emptyList();
        } else {
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
        }
    }

    public Level getLevel() {
        return level;
    }

    public List<OcrTokenBox> getTokens() {
        return tokens;
    }

    public static OcrTokenMeta fromMetas(Object metas) {
        if (metas instanceof OcrTokenMeta) {
            return (OcrTokenMeta) metas;
        }
        return null;
    }
}
