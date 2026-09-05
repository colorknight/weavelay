package com.weavelay.core.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 树形文本格式（表达从属 / 集合）↔ {@link FamilyOutputDefinition}。
 * <pre>
 * 零件:
 *   产品代号
 *   零部件名称
 *
 *   工序[]:                 # [] = 集合，挂在零件下
 *     来源: 工序目录表
 *     键: 工序号
 *     工序号
 *     工序名称
 *
 *     机加工序:             # 卡片，从属于每个工序条目
 *
 * 页写入:
 *   封面 → 零件
 *   工序目录 → 零件.工序[]
 *   机加工序卡片 → 零件.工序[].机加工序  键=工序号
 * </pre>
 */
public final class FamilyOutputDefinitionText {

    private FamilyOutputDefinitionText() {
    }

    public static String format(FamilyOutputDefinition def) {
        if (def == null) {
            return sampleText();
        }
        if (def.collections == null) {
            def.collections = new LinkedHashMap<>();
        }
        def.migrateTablesToCollections();
        String root = blankTo(def.root, "零件");

        StringBuilder sb = new StringBuilder();
        sb.append("# 缩进表示从属；名[] 表示集合；集合下无[]的块是条目卡片\n");
        sb.append("# 页写入用路径：根 / 根.集合[] / 根.集合[].卡片\n\n");

        sb.append(root).append(":\n");
        if (def.product != null) {
            for (String p : def.product) {
                if (p != null && !p.isBlank()) {
                    sb.append("  ").append(p.trim()).append('\n');
                }
            }
        }
        sb.append('\n');

        if (def.collections != null) {
            for (Map.Entry<String, FamilyOutputDefinition.CollectionDef> e : def.collections.entrySet()) {
                String name = e.getKey();
                FamilyOutputDefinition.CollectionDef c = e.getValue();
                if (name == null || name.isBlank() || c == null) {
                    continue;
                }
                sb.append("  ").append(name.trim()).append("[]:\n");
                String src = c.sourceTable == null ? "" : c.sourceTable.trim();
                if (!src.isEmpty() && !src.equals(name.trim()) && !src.equals(name.trim() + "表")) {
                    sb.append("    来源: ").append(src).append('\n');
                } else if (!src.isEmpty()) {
                    sb.append("    来源: ").append(src).append('\n');
                }
                String key = firstNonBlank(c.matchKey, c.entryId);
                if (!key.isEmpty()) {
                    sb.append("    键: ").append(key).append('\n');
                }
                if (c.columns != null) {
                    for (String col : c.columns) {
                        if (col != null && !col.isBlank()) {
                            sb.append("    ").append(col.trim()).append('\n');
                        }
                    }
                }
                if (c.cards != null && !c.cards.isEmpty()) {
                    sb.append('\n');
                    for (Map.Entry<String, FamilyOutputDefinition.CardDef> ce : c.cards.entrySet()) {
                        if (ce.getKey() == null || ce.getKey().isBlank()) {
                            continue;
                        }
                        sb.append("    ").append(ce.getKey().trim()).append(":\n");
                        FamilyOutputDefinition.CardDef card = ce.getValue();
                        if (card != null && card.fields != null) {
                            for (String f : card.fields) {
                                if (f != null && !f.isBlank()) {
                                    sb.append("      ").append(f.trim()).append('\n');
                                }
                            }
                        }
                    }
                }
                sb.append('\n');
            }
        }

        sb.append("页写入:\n");
        if (def.pageWrites != null) {
            for (FamilyOutputDefinition.PageWriteDef p : def.pageWrites) {
                if (p == null || p.pageKind == null || p.pageKind.isBlank()) {
                    continue;
                }
                sb.append("  ").append(p.pageKind.trim()).append(" → ");
                sb.append(pathForWrite(root, p));
                String mode = normalizeMode(p.mode);
                if ("patchEntry".equals(mode) && p.matchKey != null && !p.matchKey.isBlank()) {
                    sb.append("  键=").append(p.matchKey.trim());
                }
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing() + "\n";
    }

    public static FamilyOutputDefinition parse(String text) {
        FamilyOutputDefinition def = new FamilyOutputDefinition();
        def.version = 2;
        if (text == null || text.isBlank()) {
            return def;
        }

        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String noComment = raw;
            int hash = indexOfComment(raw);
            if (hash >= 0) {
                noComment = raw.substring(0, hash);
            }
            String trimmed = noComment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = leadingSpaces(noComment.replace("\t", "  "));
            lines.add(new Line(indent, trimmed));
        }

        String section = ""; // root | writes
        String currentCollection = null;
        FamilyOutputDefinition.CollectionDef currentCol = null;
        String currentCard = null;
        FamilyOutputDefinition.CardDef currentCardDef = null;
        int rootIndent = -1;
        int colIndent = -1;
        int cardIndent = -1;

        for (Line line : lines) {
            String t = line.text;

            if (isWritesHeader(t)) {
                flushCard(currentCol, currentCard, currentCardDef);
                flushCollection(def, currentCollection, currentCol);
                currentCollection = null;
                currentCol = null;
                currentCard = null;
                currentCardDef = null;
                section = "writes";
                continue;
            }

            if ("writes".equals(section) || t.contains("→") || t.contains("->")) {
                section = "writes";
                FamilyOutputDefinition.PageWriteDef pw = parseWriteLine(def.root, t);
                if (pw != null) {
                    def.pageWrites.add(pw);
                }
                continue;
            }

            // 根实体:  零件:
            if (t.endsWith(":") && !t.contains("[]") && line.indent == 0
                    && !isMetaKey(t) && !isWritesHeader(t)) {
                flushCard(currentCol, currentCard, currentCardDef);
                flushCollection(def, currentCollection, currentCol);
                currentCollection = null;
                currentCol = null;
                currentCard = null;
                currentCardDef = null;
                def.root = t.substring(0, t.length() - 1).trim();
                section = "root";
                rootIndent = line.indent;
                continue;
            }

            // 集合 工序[]:
            if (t.contains("[]") && t.endsWith(":")) {
                flushCard(currentCol, currentCard, currentCardDef);
                flushCollection(def, currentCollection, currentCol);
                currentCard = null;
                currentCardDef = null;
                String name = t.substring(0, t.indexOf('[')).trim();
                currentCollection = name;
                currentCol = new FamilyOutputDefinition.CollectionDef();
                colIndent = line.indent;
                cardIndent = -1;
                section = "collection";
                continue;
            }

            // 集合内：来源 / 键 / 列 / 卡片块
            if ("collection".equals(section) && currentCol != null) {
                // 退出集合（缩进回到根属性）
                if (line.indent <= rootIndent && rootIndent >= 0) {
                    flushCard(currentCol, currentCard, currentCardDef);
                    flushCollection(def, currentCollection, currentCol);
                    currentCollection = null;
                    currentCol = null;
                    currentCard = null;
                    currentCardDef = null;
                    section = "root";
                    // fall through to treat as root attr? unlikely at indent 0 with content
                } else if (currentCard != null && line.indent > cardIndent && cardIndent >= 0) {
                    // 卡片字段
                    if (!isMetaKey(t)) {
                        currentCardDef.fields.add(stripBullet(t.replace(":", "").trim()));
                    }
                    continue;
                } else if (t.endsWith(":") && !t.contains("[]") && !isMetaKey(t)
                        && line.indent > colIndent) {
                    // 新卡片
                    flushCard(currentCol, currentCard, currentCardDef);
                    currentCard = t.substring(0, t.length() - 1).trim();
                    currentCardDef = new FamilyOutputDefinition.CardDef();
                    cardIndent = line.indent;
                    continue;
                } else if (startsWithKey(t, "来源", "表", "source", "sourcetable")) {
                    flushCard(currentCol, currentCard, currentCardDef);
                    currentCard = null;
                    currentCardDef = null;
                    currentCol.sourceTable = afterKey(t);
                    continue;
                } else if (startsWithKey(t, "键", "匹配", "主键", "key", "match", "id", "entryid")) {
                    flushCard(currentCol, currentCard, currentCardDef);
                    currentCard = null;
                    currentCardDef = null;
                    String v = afterKey(t);
                    currentCol.matchKey = v;
                    currentCol.entryId = v;
                    continue;
                } else if (startsWithKey(t, "列", "columns", "cols")) {
                    flushCard(currentCol, currentCard, currentCardDef);
                    currentCard = null;
                    currentCardDef = null;
                    currentCol.columns.addAll(splitCsv(afterKey(t)));
                    continue;
                } else if (!t.endsWith(":") || t.contains(",")) {
                    flushCard(currentCol, currentCard, currentCardDef);
                    currentCard = null;
                    currentCardDef = null;
                    if (t.contains(",") && !t.contains("=")) {
                        currentCol.columns.addAll(splitCsv(t));
                    } else {
                        currentCol.columns.add(stripBullet(t));
                    }
                    continue;
                }
            }

            if ("root".equals(section) || section.isEmpty()) {
                if (section.isEmpty() && t.endsWith(":") && line.indent == 0) {
                    def.root = t.substring(0, t.length() - 1).trim();
                    section = "root";
                    rootIndent = line.indent;
                    continue;
                }
                if (!t.endsWith(":") || t.contains(",")) {
                    if (t.contains(",") && !t.contains("=")) {
                        def.product.addAll(splitCsv(t));
                    } else {
                        def.product.add(stripBullet(t));
                    }
                }
            }
        }
        flushCard(currentCol, currentCard, currentCardDef);
        flushCollection(def, currentCollection, currentCol);

        // 缺省来源表名
        for (Map.Entry<String, FamilyOutputDefinition.CollectionDef> e : def.collections.entrySet()) {
            FamilyOutputDefinition.CollectionDef c = e.getValue();
            if (c.sourceTable == null || c.sourceTable.isBlank()) {
                c.sourceTable = e.getKey().endsWith("表") ? e.getKey() : e.getKey() + "表";
            }
            if ((c.matchKey == null || c.matchKey.isBlank()) && !c.columns.isEmpty()) {
                c.matchKey = c.columns.get(0);
                c.entryId = c.matchKey;
            }
        }
        def.syncTablesFromCollections();
        return def;
    }

    public static String sampleText() {
        return format(FamilyOutputDefinitionFormat.sample());
    }

    private static String pathForWrite(String root, FamilyOutputDefinition.PageWriteDef p) {
        String mode = normalizeMode(p.mode);
        if (p.target != null && !p.target.isBlank()
                && (p.target.contains(".") || p.target.equals(root)
                || p.target.contains("[]"))) {
            return ensureCollectionBrackets(p.target.trim());
        }
        if ("setScalars".equals(mode)) {
            return root;
        }
        String col = firstNonBlank(p.collection, "");
        if (col.isEmpty() && p.target != null && !p.target.isBlank()) {
            // 旧：target=工序目录表
            col = stripTableSuffix(p.target.trim());
        }
        if ("mergeTable".equals(mode)) {
            return root + "." + col + "[]";
        }
        if ("patchEntry".equals(mode)) {
            String card = firstNonBlank(p.card, "卡片");
            return root + "." + col + "[]." + card;
        }
        return root;
    }

    private static String ensureCollectionBrackets(String path) {
        // 零件.工序.机加工序 → 零件.工序[].机加工序
        // 零件.工序 → 零件.工序[]
        String[] parts = path.replace("[]", "").split("\\.");
        if (parts.length == 1) {
            return parts[0];
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        if (parts.length >= 2) {
            sb.append('.').append(parts[1]).append("[]");
        }
        for (int i = 2; i < parts.length; i++) {
            sb.append('.').append(parts[i]);
        }
        return sb.toString();
    }

    private static FamilyOutputDefinition.PageWriteDef parseWriteLine(String root, String trimmed) {
        String t = trimmed.replace("->", "→");
        int arrow = t.indexOf('→');
        int eq = arrow < 0 ? indexOfAssign(t) : -1;
        String left;
        String right;
        if (arrow > 0) {
            left = t.substring(0, arrow).trim();
            right = t.substring(arrow + 1).trim();
        } else if (eq > 0) {
            left = t.substring(0, eq).trim();
            right = t.substring(eq + 1).trim();
        } else {
            return null;
        }
        left = left.replaceFirst("(?i)^(页|page)\\s+", "").trim();
        if (left.isEmpty()) {
            return null;
        }

        FamilyOutputDefinition.PageWriteDef p = new FamilyOutputDefinition.PageWriteDef();
        p.pageKind = left;

        // 右侧可能带 键=xx
        String pathPart = right;
        String[] toks = right.split("\\s+");
        List<String> pathToks = new ArrayList<>();
        for (String tok : toks) {
            if (tok.contains("=")) {
                String k = tok.substring(0, tok.indexOf('=')).trim().toLowerCase(Locale.ROOT);
                String v = tok.substring(tok.indexOf('=') + 1).trim();
                if (k.equals("键") || k.equals("匹配") || k.equals("match") || k.equals("key")) {
                    p.matchKey = v;
                } else if (k.equals("卡片") || k.equals("card")) {
                    p.card = v;
                }
            } else if (!tok.isEmpty()) {
                pathToks.add(tok);
            }
        }
        if (!pathToks.isEmpty()) {
            // 若首词是动作名，兼容旧写法
            String first = pathToks.get(0);
            String mode = normalizeMode(first);
            if (isModeWord(first)) {
                p.mode = mode;
                pathToks.remove(0);
                if ("setScalars".equals(mode) && pathToks.isEmpty()) {
                    p.target = blankTo(root, "零件");
                } else if ("mergeTable".equals(mode) && !pathToks.isEmpty()) {
                    String col = stripTableSuffix(pathToks.get(0));
                    p.collection = col;
                    p.target = blankTo(root, "零件") + "." + col;
                } else if ("patchEntry".equals(mode)) {
                    if (!pathToks.isEmpty()) {
                        p.collection = stripTableSuffix(pathToks.get(0));
                    }
                    if (pathToks.size() >= 2) {
                        p.card = pathToks.get(1);
                    }
                    p.target = blankTo(root, "零件") + "."
                            + blankTo(p.collection, "工序") + "."
                            + blankTo(p.card, "卡片");
                }
            } else {
                pathPart = String.join(" ", pathToks);
                applyPath(p, blankTo(root, "零件"), pathPart);
            }
        } else {
            applyPath(p, blankTo(root, "零件"), pathPart.split("\\s+")[0]);
        }
        return p;
    }

    private static void applyPath(FamilyOutputDefinition.PageWriteDef p, String root, String path) {
        String clean = path.replace("[]", "").trim();
        p.target = clean;
        String[] parts = clean.split("\\.");
        if (parts.length <= 1) {
            p.mode = "setScalars";
            p.target = parts.length == 1 && !parts[0].isEmpty() ? parts[0] : root;
            return;
        }
        p.collection = parts[1];
        if (parts.length == 2) {
            p.mode = "mergeTable";
            return;
        }
        p.mode = "patchEntry";
        p.card = parts[parts.length - 1];
        if (p.matchKey == null) {
            p.matchKey = "";
        }
    }

    private static void flushCollection(
            FamilyOutputDefinition def, String name, FamilyOutputDefinition.CollectionDef col) {
        if (name != null && !name.isBlank() && col != null) {
            def.collections.put(name.trim(), col);
        }
    }

    private static void flushCard(
            FamilyOutputDefinition.CollectionDef col, String name, FamilyOutputDefinition.CardDef card) {
        if (col != null && name != null && !name.isBlank() && card != null) {
            if (col.cards == null) {
                col.cards = new LinkedHashMap<>();
            }
            col.cards.put(name.trim(), card);
        }
    }

    private static boolean isWritesHeader(String t) {
        String base = t.endsWith(":") ? t.substring(0, t.length() - 1).trim() : t;
        return base.equals("页写入") || base.equals("写入") || base.equalsIgnoreCase("writes")
                || base.equalsIgnoreCase("pages") || base.equalsIgnoreCase("pagewrites");
    }

    private static boolean isMetaKey(String t) {
        return startsWithKey(t, "来源", "表", "键", "匹配", "主键", "列",
                "source", "key", "match", "columns", "cols");
    }

    private static boolean isModeWord(String raw) {
        String m = raw.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "填标量", "标量", "setscalars", "set", "scalars",
                 "合表", "并表", "mergetable", "merge", "table",
                 "补丁", "补丁条目", "patchentry", "patch", "entry" -> true;
            default -> false;
        };
    }

    private static String normalizeMode(String raw) {
        if (raw == null) {
            return "setScalars";
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "填标量", "标量", "setscalars", "set", "scalars" -> "setScalars";
            case "合表", "并表", "mergetable", "merge", "table" -> "mergeTable";
            case "补丁", "补丁条目", "patchentry", "patch", "entry" -> "patchEntry";
            default -> raw.trim();
        };
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static int indexOfComment(String raw) {
        boolean inQuote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfAssign(String s) {
        int spaced = s.indexOf(" = ");
        if (spaced > 0) {
            return spaced + 1;
        }
        return s.indexOf('=');
    }

    private static boolean startsWithKey(String trimmed, String... keys) {
        for (String k : keys) {
            if (trimmed.regionMatches(true, 0, k, 0, k.length())) {
                char next = trimmed.length() > k.length() ? trimmed.charAt(k.length()) : 0;
                if (next == ':' || next == '：' || next == ' ' || next == '=' || next == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String afterKey(String trimmed) {
        int c = indexOfKeySep(trimmed);
        return c < 0 ? "" : trimmed.substring(c + 1).trim();
    }

    private static int indexOfKeySep(String s) {
        int best = -1;
        for (int x : new int[] {s.indexOf(':'), s.indexOf('：'), s.indexOf('=')}) {
            if (x >= 0 && (best < 0 || x < best)) {
                best = x;
            }
        }
        return best;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String p : s.split("[,，、]")) {
            String t = stripBullet(p.trim());
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String stripBullet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("-") || t.startsWith("•") || t.startsWith("*")) {
            t = t.substring(1).trim();
        }
        return t;
    }

    private static String stripTableSuffix(String name) {
        if (name != null && name.endsWith("表") && name.length() > 1) {
            return name.substring(0, name.length() - 1);
        }
        return name == null ? "" : name;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private record Line(int indent, String text) {
    }
}
