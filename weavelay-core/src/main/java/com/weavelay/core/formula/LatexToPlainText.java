package com.weavelay.core.formula;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将公式识别产出的 LaTeX 转成普通可读文字（原位替换，不打乱混排/表格）。
 * <p>
 * 弹窗、缓存、Excel 统一用线性写法 {@code ⌀134_{0}^{+0.3}}（准确、可编辑）。
 * {@link #toExcelText(String)} 仍可把线性转成 Unicode 上下标，默认不走。
 */
public final class LatexToPlainText {

    private static final Pattern CMD_BRACE = Pattern.compile(
            "\\\\(mathrm|mathbf|mathit|mathsf|textrm|textbf|text|operatorname)\\s*\\{([^{}]*)\\}");
    private static final Pattern SUB = Pattern.compile("_\\s*\\{([^{}]*)\\}|_\\s*([0-9a-zA-Z+\\-.=±()])");
    private static final Pattern SUP = Pattern.compile("\\^\\s*\\{([^{}]*)\\}|\\^\\s*([0-9a-zA-Z+\\-.=±()])");
    private static final Pattern FRAC = Pattern.compile("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}");

    private static final Map<String, String> CMD = new HashMap<>();

    static {
        CMD.put("varnothing", "⌀");
        CMD.put("emptyset", "⌀");
        CMD.put("oslash", "⌀");
        CMD.put("diameter", "⌀");
        // 个别模型把直径误成 \otimes
        CMD.put("otimes", "⌀");
        CMD.put("phi", "φ");
        CMD.put("varphi", "φ");
        CMD.put("Phi", "Φ");
        CMD.put("pm", "±");
        CMD.put("mp", "∓");
        CMD.put("times", "×");
        CMD.put("cdot", "·");
        CMD.put("div", "÷");
        CMD.put("sim", "～");
        CMD.put("approx", "≈");
        CMD.put("leq", "≤");
        CMD.put("le", "≤");
        CMD.put("geq", "≥");
        CMD.put("ge", "≥");
        CMD.put("circ", "°");
        CMD.put("degree", "°");
        CMD.put("mu", "μ");
        CMD.put("Omega", "Ω");
        CMD.put("alpha", "α");
        CMD.put("beta", "β");
        CMD.put("gamma", "γ");
        CMD.put("delta", "δ");
        CMD.put("theta", "θ");
        CMD.put("infty", "∞");
        CMD.put("perp", "⊥");
        CMD.put("angle", "∠");
        CMD.put("dagger", "+");
        CMD.put("quad", " ");
        CMD.put("qquad", " ");
        CMD.put("{", "{");
        CMD.put("}", "}");
        CMD.put("%", "%");
        CMD.put("&", "&");
        CMD.put("#", "#");
        CMD.put("_", "_");
        CMD.put(",", "");
        CMD.put(";", "");
        CMD.put("!", "");
        CMD.put(":", "");
    }

    private LatexToPlainText() {}

    /** 原位替换数学片段；前后汉字 / 表格结构不动。线性上下标。 */
    public static String convert(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        // 旧缓存里的 Unicode 上下标 → 线性写法，避免一前一后看不清
        String s = unicodeScriptsToLinear(text);
        if (s.indexOf('$') < 0 && s.indexOf('\\') < 0) {
            // 无 LaTeX：仍要修 OCR/旧缓存里的 α13 → ⌀13
            return fixDiameterGlyphAliases(ensureTolerancePlus(fixDiameterBarOne(s)));
        }
        s = convertByScan(s);
        if (isPureLatexFragment(s)) {
            s = convertLatexBody(s);
        }
        // 缓存/旧结果里也可能残留 {{}}
        s = flattenRedundantBraces(s);
        s = fixDiameterBarOne(s);
        s = fixDiameterGlyphAliases(s);
        s = ensureTolerancePlus(s);
        return s;
    }

    /**
     * 写出 Excel 用纯文本：先 {@link #convert}，再把线性 {@code _{}/{^ {}} 换成 Unicode 上下标字形。
     * 例：{@code ⌀134_{0}^{+0.3}} → {@code ⌀134₀⁺⁰·³}
     */
    public static String toExcelText(String text) {
        return linearScriptsToUnicode(convert(text));
    }

    /**
     * 线性 {@code _{0}^{+0.3}} → Unicode {@code ₀⁺⁰·³}（小数点用中间点 ·）。
     */
    static String linearScriptsToUnicode(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        final int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if ((c == '_' || c == '^') && i + 1 < n && text.charAt(i + 1) == '{') {
                boolean sup = c == '^';
                int close = findClosingBrace(text, i + 2);
                if (close < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                String body = text.substring(i + 2, close);
                if (sup && body.startsWith("±")) {
                    body = "+" + body.substring(1);
                }
                out.append(mapScriptBody(body, sup));
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String mapScriptBody(String body, boolean superscript) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            sb.append(toScriptChar(body.charAt(i), superscript));
        }
        return sb.toString();
    }

    private static char toScriptChar(char c, boolean superscript) {
        if (superscript) {
            return switch (c) {
                case '0' -> '⁰';
                case '1' -> '¹';
                case '2' -> '²';
                case '3' -> '³';
                case '4' -> '⁴';
                case '5' -> '⁵';
                case '6' -> '⁶';
                case '7' -> '⁷';
                case '8' -> '⁸';
                case '9' -> '⁹';
                case '+' -> '⁺';
                case '-' -> '⁻';
                case '=' -> '⁼';
                case '(' -> '⁽';
                case ')' -> '⁾';
                case '.' -> '·';
                case '±' -> '⁺';
                default -> c;
            };
        }
        return switch (c) {
            case '0' -> '₀';
            case '1' -> '₁';
            case '2' -> '₂';
            case '3' -> '₃';
            case '4' -> '₄';
            case '5' -> '₅';
            case '6' -> '₆';
            case '7' -> '₇';
            case '8' -> '₈';
            case '9' -> '₉';
            case '+' -> '₊';
            case '-' -> '₋';
            case '=' -> '₌';
            case '(' -> '₍';
            case ')' -> '₎';
            default -> c;
        };
    }

    private static int findClosingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String convertByScan(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        final int n = text.length();
        while (i < n) {
            if (i + 1 < n && text.charAt(i) == '$' && text.charAt(i + 1) == '$') {
                int end = text.indexOf("$$", i + 2);
                if (end >= 0) {
                    out.append(convertLatexBody(text.substring(i + 2, end)));
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '$') {
                int end = text.indexOf('$', i + 1);
                if (end > i) {
                    out.append(convertLatexBody(text.substring(i + 1, end)));
                    i = end + 1;
                    continue;
                }
                i++;
                continue;
            }
            if (text.charAt(i) == '\\' && isLatexCommandStart(text, i)) {
                int end = findBareLatexEnd(text, i);
                out.append(convertLatexBody(text.substring(i, end)));
                i = end;
                continue;
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static boolean isLatexCommandStart(String s, int i) {
        if (i + 1 >= s.length() || s.charAt(i) != '\\') {
            return false;
        }
        char c = s.charAt(i + 1);
        if (!Character.isLetter(c)) {
            return false;
        }
        int j = i + 1;
        while (j < s.length() && Character.isLetter(s.charAt(j))) {
            j++;
        }
        String name = s.substring(i + 1, j);
        return CMD.containsKey(name)
                || name.equals("mathrm") || name.equals("mathbf")
                || name.equals("frac") || name.equals("left") || name.equals("right");
    }

    private static int findBareLatexEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '$' || c == '|' || c == '\n' || c == '\r') {
                break;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                break;
            }
            if (c == '，' || c == '。' || c == '；' || c == '、') {
                break;
            }
            i++;
        }
        return i;
    }

    private static boolean isPureLatexFragment(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        if (s.indexOf('|') >= 0 || s.indexOf('\n') >= 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return s.indexOf('\\') >= 0;
    }

    /** 纯公式体 → 普通文字（线性上下标）。 */
    public static String convertLatexBody(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String s = preprocessLatex(latex.trim());
        s = DiameterLatexNormalizer.normalize(s);
        s = preprocessLatex(s);
        for (int pass = 0; pass < 8; pass++) {
            String prev = s;
            s = flattenRedundantBraces(s);
            s = replaceAll(FRAC, s, m -> convertLatexBody(m.group(1)) + "/" + convertLatexBody(m.group(2)));
            s = replaceAll(CMD_BRACE, s, m -> convertLatexBody(m.group(2)));
            s = replaceAll(SUB, s, m -> formatScript(convertLatexBody(group(m)), false));
            s = replaceAll(SUP, s, m -> formatScript(convertLatexBody(group(m)), true));
            s = replaceSimpleCommands(s);
            if (s.equals(prev)) {
                break;
            }
        }
        s = flattenRedundantBraces(s);
        s = collapseDiameterDigits(s);
        s = fixDiameterBarOneInFragment(s);
        s = fixDiameterGlyphAliases(s);
        s = ensureTolerancePlus(s);
        return tidyFragment(s);
    }

    /**
     * 直径同形溃点：公式/OCR 常把 ⌀ 认成 α/ø/∅/φ，紧跟尺寸数字时收成 ⌀。
     * 例：{@code α13_{0}^{+0.3}} → {@code ⌀13_{0}^{+0.3}}
     */
    static String fixDiameterGlyphAliases(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        // α ø Ø ∅ ϕ φ Φ（不含单独出现的希腊字母用途，仅数字前）
        return s.replaceAll("[αøØ∅ϕφΦ](?=\\d)", "⌀");
    }

    /**
     * 压空格；{@code \dagger}/{@code \pm} 在上标公差里当成 {@code +}（模型常误认）。
     */
    static String preprocessLatex(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s;
        t = t.replace("\\dagger", "+");
        t = t.replace("\\ast", "+");
        t = t.replace("\\star", "+");
        // 上标公差：^{\pm0.3} / ^\pm → ^{+
        t = t.replaceAll("\\^\\s*\\{\\s*\\\\pm", "^{+");
        t = t.replaceAll("\\^\\s*\\\\pm", "^+");
        t = t.replace("\\,", " ");
        t = t.replace("\\:", " ");
        t = t.replace("\\;", " ");
        t = t.replace("\\!", "");
        t = t.replace("\\quad", " ");
        t = t.replace("\\qquad", " ");
        t = t.replaceAll("\\s*_\\s*", "_");
        t = t.replaceAll("\\s*\\^\\s*", "^");
        t = t.replaceAll("_\\s*\\{\\s*", "_{");
        t = t.replaceAll("\\^\\s*\\{\\s*", "^{");
        t = t.replaceAll("\\{\\s+", "{");
        t = t.replaceAll("\\s+\\}", "}");
        t = flattenRedundantBraces(t);
        t = t.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        t = t.replaceAll("\\s{2,}", " ");
        return t.trim();
    }

    /** 线性上下标：_{0} ^{+0.3}；上标里误识的 ± 改成 +。 */
    private static String formatScript(String body, boolean superscript) {
        String b = body == null ? "" : body.replaceAll("\\s+", "");
        // 模型/解码常产出 {{{0.3}}} 一类套娃花括号
        b = unwrapOuterBraces(b);
        if (superscript && b.startsWith("±")) {
            b = "+" + b.substring(1);
        }
        if (b.isEmpty()) {
            return "";
        }
        return superscript ? "^{" + b + "}" : "_{" + b + "}";
    }

    /** 反复剥掉无意义的 {{x}} → {x}，以及 ^{{{{x}}}} 里中间空套。 */
    static String flattenRedundantBraces(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        String cur = s;
        for (int i = 0; i < 12; i++) {
            String next = cur.replaceAll("\\{\\{([^{}]*)\\}\\}", "{$1}");
            if (next.equals(cur)) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    static String unwrapOuterBraces(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String b = body.trim();
        for (int i = 0; i < 8; i++) {
            if (b.length() < 2 || b.charAt(0) != '{' || b.charAt(b.length() - 1) != '}') {
                break;
            }
            String inner = b.substring(1, b.length() - 1).trim();
            // 仅当内外匹配成一层完整包裹时才剥（避免 {a}{b}）
            if (!balancedSingleWrap(b)) {
                break;
            }
            b = inner;
        }
        return b;
    }

    private static boolean balancedSingleWrap(String s) {
        if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && i != s.length() - 1) {
                    return false;
                }
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /**
     * ⌀[1] / ⌀1] → ⌀11（短直径第二个 1 常被认成括号）。
     * <p>
     * 故意不用 {@code |}：整表 Markdown 的列分隔符也是 {@code |}，
     * 若写成 {@code ⌀1|} 会把表格竖线吃掉，整表错位变空。
     * 公式片段里的 {@code ⌀|1} 见 {@link #fixDiameterBarOneInFragment}。
     */
    static String fixDiameterBarOne(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        String t = s;
        t = t.replaceAll("⌀\\s*[\\[]+\\s*1\\s*[\\]\\)]?", "⌀11");
        t = t.replaceAll("⌀\\s*1\\s*[\\]]+", "⌀11");
        t = t.replaceAll("∅\\s*[\\[]+\\s*1\\s*[\\]\\)]?", "⌀11");
        t = t.replaceAll("∅\\s*1\\s*[\\]]+", "⌀11");
        return t;
    }

    /** 单格公式体：额外把 OCR 把 1 认成竖线的 {@code ⌀|1}/{@code ⌀1|} 收成 ⌀11。 */
    static String fixDiameterBarOneInFragment(String s) {
        String t = fixDiameterBarOne(s);
        if (t.isEmpty()) {
            return t;
        }
        t = t.replaceAll("⌀\\s*\\|\\s*1", "⌀11");
        t = t.replaceAll("⌀\\s*1\\s*\\|", "⌀11");
        t = t.replaceAll("∅\\s*\\|\\s*1", "⌀11");
        t = t.replaceAll("∅\\s*1\\s*\\|", "⌀11");
        return t;
    }

    /** 机械公差：_{…}^{0.3} 漏掉 + 时补上（已有 +/- 不动）。 */
    static String ensureTolerancePlus(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        return s.replaceAll("_\\{([^}]*)\\}\\^\\{(\\d)", "_{$1}^{+$2");
    }

    private static String group(Matcher m) {
        String a = m.group(1);
        return a != null ? a : m.group(2);
    }

    private static String replaceSimpleCommands(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (!Character.isLetter(n)) {
                    out.append(CMD.getOrDefault(String.valueOf(n), ""));
                    i += 2;
                    continue;
                }
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) {
                    j++;
                }
                String name = s.substring(i + 1, j);
                String repl = CMD.get(name);
                if (repl != null) {
                    out.append(repl);
                }
                if (j < s.length() && s.charAt(j) == ' ') {
                    j++;
                }
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String collapseDiameterDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            out.append(c);
            i++;
            if (c == '⌀' || c == '∅') {
                while (i < s.length()) {
                    char n = s.charAt(i);
                    if (n == ' ' || n == '\t') {
                        i++;
                        continue;
                    }
                    if (n >= '0' && n <= '9') {
                        out.append(n);
                        i++;
                        continue;
                    }
                    break;
                }
            }
        }
        return out.toString();
    }

    /**
     * 把旧的 Unicode 上/下标串还原成线性 {@code _{}/{^ {}}，便于阅读。
     * 例：⌀148₀⁺⁰·³ → ⌀148_{0}^{+0.3}
     */
    static String unicodeScriptsToLinear(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isSubChar(c)) {
                StringBuilder body = new StringBuilder();
                while (i < text.length() && isSubChar(text.charAt(i))) {
                    body.append(fromSub(text.charAt(i)));
                    i++;
                }
                out.append("_{").append(body).append('}');
                continue;
            }
            if (isSuperChar(c)) {
                StringBuilder body = new StringBuilder();
                while (i < text.length() && isSuperChar(text.charAt(i))) {
                    body.append(fromSuper(text.charAt(i)));
                    i++;
                }
                String b = body.toString();
                if (b.startsWith("±")) {
                    b = "+" + b.substring(1);
                }
                out.append("^{").append(b).append('}');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isSubChar(char c) {
        return "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎".indexOf(c) >= 0 || c == '·' && false;
    }

    private static boolean isSuperChar(char c) {
        return "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾±".indexOf(c) >= 0 || c == '·';
    }

    private static char fromSub(char c) {
        return switch (c) {
            case '₀' -> '0';
            case '₁' -> '1';
            case '₂' -> '2';
            case '₃' -> '3';
            case '₄' -> '4';
            case '₅' -> '5';
            case '₆' -> '6';
            case '₇' -> '7';
            case '₈' -> '8';
            case '₉' -> '9';
            case '₊' -> '+';
            case '₋' -> '-';
            case '₌' -> '=';
            case '₍' -> '(';
            case '₎' -> ')';
            default -> c;
        };
    }

    private static char fromSuper(char c) {
        return switch (c) {
            case '⁰' -> '0';
            case '¹' -> '1';
            case '²' -> '2';
            case '³' -> '3';
            case '⁴' -> '4';
            case '⁵' -> '5';
            case '⁶' -> '6';
            case '⁷' -> '7';
            case '⁸' -> '8';
            case '⁹' -> '9';
            case '⁺' -> '+';
            case '⁻' -> '-';
            case '⁼' -> '=';
            case '⁽' -> '(';
            case '⁾' -> ')';
            case '·' -> '.';
            case '±' -> '+'; // 上标公差误成 ±
            default -> c;
        };
    }

    private static String tidyFragment(String s) {
        return s.replace('\u00A0', ' ')
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern p, String s, Replacer r) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
