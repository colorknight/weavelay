package com.weavelay.core.formula;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机械图⌀与数学∅同形：模型常给出 {@code \\otimes/\\beta/\\mathfrak{g}} 等，
 * 或误吞邻字后在数字前堆 {@code \\underline{\\hat{\\Delta}}} 一类胡写。
 * <p>
 * 另：短⌀（无公差）常把 {@code 11} 认成 {@code \\mathrm{\\ln}}，⌀ 认成 {@code \\beta}——
 * 仍是字形溃点别名，不写死工厂尺寸表。
 * 只改公式 LaTeX，不碰 OCR。
 */
public final class DiameterLatexNormalizer {

    /** 已知⌀溃点 → 紧跟数字 */
    private static final Pattern LEADING_ALIAS = Pattern.compile(
            "^(?:\\\\otimes|\\\\times|\\\\cdot|\\\\beta|\\\\alpha|\\\\emptyset|\\\\oslash|\\\\varphi"
                    + "|\\\\mathfrak\\s*\\{\\s*[gGbBoO]\\s*\\}"
                    + "|\\\\mathcal\\s*\\{\\s*[BObo]\\s*\\}"
                    + "|\\\\mathrm\\s*\\{\\s*[BObo]\\s*\\}"
                    + "|\\\\mathbf\\s*\\{\\s*[BObo]\\s*\\}"
                    + ")"
                    + "(\\s*\\d)");

    /** ⌀11 典型溃点：整式只有 beta + ln，无数字 */
    private static final Pattern BETA_LN = Pattern.compile(
            "^\\\\beta\\s*\\\\mathrm\\s*\\{\\s*\\\\ln\\s*\\}\\s*$");

    /** 无前导符：数字（可空格隔开）+ 下标 + 上标公差 */
    private static final Pattern BARE_DIGITS_TOLERANCE = Pattern.compile(
            "^(\\d(?:\\s*\\d)*)\\s*_\\s*\\{[\\s\\S]*\\}\\s*\\^\\s*\\{");

    /**
     * 任意前缀噪声 + 数字公差核。前缀不得含数字（避免误切真公式）。
     */
    private static final Pattern JUNK_PREFIX_THEN_DIGITS_TOL = Pattern.compile(
            "^(?:(?!\\d)[\\s\\S])*?(\\d(?:\\s*\\d)*)(\\s*_\\s*\\{[^}]*\\}\\s*\\^\\s*\\{[^}]*\\})\\s*$");

    /**
     * {@code \\varnothing 8 1 3 4 _{…}}：多出一位前导数字时，常见为邻框串墨；
     * 仅当公差前恰好 4 个单位数字时丢掉最前面那个。
     */
    private static final Pattern EXTRA_LEADING_DIGIT = Pattern.compile(
            "^(\\\\varnothing\\s+)(\\d)\\s+(\\d\\s+\\d\\s+\\d)(\\s*_\\s*\\{)");

    /**
     * ⌀11：第二个 1 被认成 {@code |/[}，常套 {@code \\left./\\right|/\\right[}。
     * 例：{@code \\left. \\varnothing \\right| 1}、{@code \\varnothing \\right[ 1}
     */
    private static final Pattern VARNOTHING_BAR_ONE = Pattern.compile(
            "^\\\\varnothing\\s*[|\\[]\\s*1\\s*$");
    private static final Pattern VARNOTHING_ONE_BAR = Pattern.compile(
            "^\\\\varnothing\\s*1\\s*[|\\[]\\s*$");

    private DiameterLatexNormalizer() {}

    public static String normalize(String latex) {
        if (latex == null || latex.isBlank()) {
            return latex == null ? "" : latex;
        }
        String s = latex.trim();
        if (BETA_LN.matcher(s).matches()) {
            return "\\varnothing 1 1";
        }
        // 先剥定界符外壳，避免 \\left./\\right| 挡住后续规则
        s = stripDelimiterShell(s);
        if (VARNOTHING_BAR_ONE.matcher(s).matches() || VARNOTHING_ONE_BAR.matcher(s).matches()) {
            return "\\varnothing 1 1";
        }
        Matcher m = LEADING_ALIAS.matcher(s);
        if (m.find()) {
            s = "\\varnothing" + m.group(1) + s.substring(m.end());
        } else if (BARE_DIGITS_TOLERANCE.matcher(s).find()
                && !s.contains("varnothing") && !s.contains("emptyset")) {
            s = "\\varnothing " + s;
        } else {
            Matcher junk = JUNK_PREFIX_THEN_DIGITS_TOL.matcher(s);
            if (junk.matches()
                    && !s.contains("varnothing") && !s.contains("emptyset")) {
                s = "\\varnothing " + junk.group(1) + junk.group(2);
            }
        }
        int idx = s.indexOf("\\varnothing");
        if (idx > 0) {
            String rest = s.substring(idx);
            if (rest.matches("(?s)\\\\varnothing\\s*\\d.*")
                    || rest.matches("(?s)\\\\varnothing\\s*[|\\[].*")) {
                String prefix = s.substring(0, idx);
                if (!prefix.matches(".*\\d.*")) {
                    s = rest.trim();
                }
            }
        }
        s = stripDelimiterShell(s);
        if (VARNOTHING_BAR_ONE.matcher(s).matches() || VARNOTHING_ONE_BAR.matcher(s).matches()) {
            return "\\varnothing 1 1";
        }
        Matcher after = JUNK_PREFIX_THEN_DIGITS_TOL.matcher(s);
        if (!s.contains("varnothing") && after.matches()) {
            s = "\\varnothing " + after.group(1) + after.group(2);
        }
        Matcher extra = EXTRA_LEADING_DIGIT.matcher(s);
        if (extra.find()) {
            s = extra.group(1) + extra.group(3) + extra.group(4) + s.substring(extra.end());
        }
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * UniMERNet 常给 ⌀ 两侧加空定界符（{@code \\left. \\right| \\right[} 等），与涂白无关。
     * 竖线/左括号保留成 {@code |/[}，供 ⌀11 规则认作第二个 1。
     */
    static String stripDelimiterShell(String s) {
        if (s == null || s.isBlank()) {
            return s == null ? "" : s;
        }
        String t = s;
        t = t.replaceAll("\\\\left\\s*\\.", " ");
        t = t.replaceAll("\\\\right\\s*\\.", " ");
        t = t.replaceAll("\\\\left\\s*\\|", " | ");
        t = t.replaceAll("\\\\right\\s*\\|", " | ");
        t = t.replaceAll("\\\\left\\s*\\[", " [ ");
        t = t.replaceAll("\\\\right\\s*\\[", " [ ");
        t = t.replaceAll("\\\\left\\s*\\]", " ");
        t = t.replaceAll("\\\\right\\s*\\]", " ");
        t = t.replaceAll("\\\\left\\s*\\(", " ");
        t = t.replaceAll("\\\\right\\s*\\)", " ");
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * 公式结果仍含定界符噪声或几乎无数字时，值得用 OCR 抽数字补救。
     */
    public static boolean needsDigitSalvage(String latex) {
        if (latex == null || latex.isBlank()) {
            return true;
        }
        if (!hasArabicDigit(latex)) {
            return true;
        }
        String s = latex.toLowerCase();
        return s.contains("\\left") || s.contains("\\right");
    }

    /** 公式结果里是否已有阿拉伯数字。 */
    public static boolean hasArabicDigit(String latex) {
        if (latex == null) {
            return false;
        }
        for (int i = 0; i < latex.length(); i++) {
            char c = latex.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }
}
