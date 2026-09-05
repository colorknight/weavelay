package com.weavelay.core.page;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本归一化与槽位行引用解析（如输出定义中的 {@code #N}）。
 * <p>页型自动分类（OCR 后按锚点规则匹配）已移除；页型由用户手动选择。
 */
public final class PageClassifier {

    private static final Pattern LINE_REF = Pattern.compile("^#(\\d+)$");

    private PageClassifier() {
    }

    /** 槽位代码形如 {@code #4} 时解析为行号（从 1 起）；否则返回 null。 */
    public static Integer parseLineRef(String token) {
        if (token == null) {
            return null;
        }
        Matcher matcher = LINE_REF.matcher(token.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            int lineNo = Integer.parseInt(matcher.group(1));
            return lineNo >= 1 ? lineNo : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").trim();
    }
}
