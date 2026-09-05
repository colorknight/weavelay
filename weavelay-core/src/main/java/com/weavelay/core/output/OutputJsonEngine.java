package com.weavelay.core.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按输出定义模板 + 页值袋，渐进填充「实际 JSON」。
 * <p>
 * 顺序：本页填充集合 → {@code route}（目录分流建壳）→ {@code link}（回填）。
 * 集合按 {@code group.key} upsert，后续页只补字段、不整段重写。
 */
public final class OutputJsonEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonNode definition;
    private ObjectNode document;

    public void reset(String definitionJson) throws Exception {
        if (definitionJson == null || definitionJson.isBlank()) {
            definition = MAPPER.createObjectNode();
            document = MAPPER.createObjectNode();
            return;
        }
        definition = MAPPER.readTree(definitionJson.trim());
        document = (ObjectNode) definition.deepCopy();
        stripPlaceholdersAndClearCollections(document);
    }

    /** 已有实际 JSON 时只刷新定义（模板 / link），不丢已填内容。 */
    public void refreshDefinition(String definitionJson) throws Exception {
        if (definitionJson == null || definitionJson.isBlank()) {
            return;
        }
        definition = MAPPER.readTree(definitionJson.trim());
        if (document == null) {
            document = (ObjectNode) definition.deepCopy();
            stripPlaceholdersAndClearCollections(document);
        }
    }

    public boolean hasDocument() {
        return document != null && !document.isEmpty();
    }

    public ObjectNode document() {
        return document;
    }

    public String toPrettyJson() {
        if (document == null) {
            return "{}";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (Throwable ex) {
            return prettyNode(document, 0) + "\n";
        }
    }

    private static String prettyNode(JsonNode node, int depth) {
        String pad = "  ".repeat(depth);
        String pad1 = "  ".repeat(depth + 1);
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isTextual()) {
            return quote(node.asText());
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < node.size(); i++) {
                sb.append(pad1).append(prettyNode(node.get(i), depth + 1));
                if (i < node.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(pad).append(']');
            return sb.toString();
        }
        if (node.isObject()) {
            if (node.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{\n");
            java.util.List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                sb.append(pad1).append(quote(name)).append(": ")
                        .append(prettyNode(node.get(name), depth + 1));
                if (i < names.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(pad).append('}');
            return sb.toString();
        }
        return String.valueOf(node);
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * 用本页槽位/表数据填充；随后执行 link.set 回填。
     */
    public void applyPage(PageValueBag bag) {
        if (document == null || definition == null || bag == null) {
            return;
        }
        if (!(definition instanceof ObjectNode defRoot)) {
            return;
        }
        fillObject(defRoot, document, bag, null);
        applyRoutes();
        applyLinks();
    }

    private void fillObject(ObjectNode template, ObjectNode target, PageValueBag bag, Map<String, String> rowCtx) {
        Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            if ("link".equals(key) || "group".equals(key) || "route".equals(key)
                    || "version".equals(key) && e.getValue().isNumber()) {
                if ("version".equals(key) && e.getValue().isNumber()) {
                    target.set(key, e.getValue().deepCopy());
                }
                continue;
            }
            JsonNode t = e.getValue();
            if (t == null || t.isNull()) {
                continue;
            }
            if (t.isTextual()) {
                putResolvedText(target, key, t.asText(), bag, rowCtx);
                continue;
            }
            if (t.isArray()) {
                fillArray(key, (ArrayNode) t, target, bag, rowCtx);
                continue;
            }
            if (t.isObject()) {
                ObjectNode childTarget = target.has(key) && target.get(key).isObject()
                        ? (ObjectNode) target.get(key)
                        : target.putObject(key);
                fillObject((ObjectNode) t, childTarget, bag, rowCtx);
            }
        }
    }

    /**
     * 占位符优先；模板为 "" 时用字段名从当前行/页标量取（如工序号）。
     */
    private void putResolvedText(
            ObjectNode target, String fieldKey, String templateText,
            PageValueBag bag, Map<String, String> rowCtx) {
        String resolved = resolveText(templateText, bag, rowCtx);
        if (resolved != null) {
            target.put(fieldKey, stripNewlines(resolved));
            return;
        }
        if (templateText != null && PLACEHOLDER.matcher(templateText).find()) {
            return;
        }
        if (rowCtx != null && rowCtx.containsKey(fieldKey)) {
            String v = rowCtx.get(fieldKey);
            if (v != null && !v.isBlank()) {
                target.put(fieldKey, stripNewlines(v));
                return;
            }
        }
        if (bag != null) {
            String s = bag.scalar(fieldKey);
            if (s != null && !s.isBlank()) {
                target.put(fieldKey, stripNewlines(s));
            }
        }
    }

    /** upsert 前补齐 group/link 匹配键（模板里工序号常写成 ""）。 */
    private void enrichMatchKeyFromBag(ObjectNode built, String collectionKey, PageValueBag bag) {
        if (built == null || bag == null || collectionKey == null) {
            return;
        }
        String mf = upsertFieldForCollection(collectionKey);
        if (mf == null || mf.isBlank()) {
            return;
        }
        if (!built.path(mf).asText("").isBlank()) {
            return;
        }
        String s = bag.scalar(mf);
        if (s != null && !s.isBlank()) {
            built.put(mf, s);
        }
    }

    private void fillArray(
            String key,
            ArrayNode templateArr,
            ObjectNode parent,
            PageValueBag bag,
            Map<String, String> rowCtx) {
        if (templateArr.isEmpty()) {
            if (!parent.has(key) || !parent.get(key).isArray()) {
                parent.putArray(key);
            }
            return;
        }
        JsonNode elemTemplate = templateArr.get(0);
        ArrayNode targetArr = parent.has(key) && parent.get(key).isArray()
                ? (ArrayNode) parent.get(key)
                : parent.putArray(key);

        if (!elemTemplate.isObject()) {
            return;
        }
        ObjectNode elemObj = (ObjectNode) elemTemplate;
        List<String> nestedKeysEarly = nestedArrayKeys(elemObj);
        // 父级表：不含嵌套数组里的表，避免「辅助材料」被当成机械加工主键表
        List<String> parentTables = referencedParentTables(elemObj, nestedKeysEarly);
        List<String> tableNames = !parentTables.isEmpty()
                ? parentTables
                : (nestedKeysEarly.isEmpty() ? referencedTables(elemObj) : List.of());
        String primary = null;
        for (String tn : tableNames) {
            if (bag.hasTable(tn) && !bag.tableRows(tn).isEmpty()) {
                primary = tn;
                break;
            }
        }

        if (primary != null) {
            // 已在父行上下文中：若主键表字段能被当前行覆盖，只生成 1 条，避免把整表再嵌一遍
            if (rowCtx != null && rowCtxCoversTable(elemObj, primary, rowCtx)) {
                String other = null;
                for (String tn : tableNames) {
                    if (!tn.equals(primary) && bag.hasTable(tn) && !bag.tableRows(tn).isEmpty()) {
                        other = tn;
                        break;
                    }
                }
                if (other == null) {
                    ObjectNode built = MAPPER.createObjectNode();
                    fillObject(elemObj, built, bag, rowCtx);
                    String matchField = upsertFieldForCollection(key);
                    enrichMatchKeyFromBag(built, key, bag);
                    upsert(targetArr, built, matchField);
                    coalesceIfToolArray(key, targetArr);
                    return;
                }
                primary = other;
            }
            List<String> nestedKeys = nestedArrayKeys(elemObj);
            List<Map<String, String>> flatRows = bag.tableRows(primary);
            String collectionMatch = upsertFieldForCollection(key);
            String pkCol = resolveGroupKey(key, elemObj, nestedKeys);
            String matchField = resolveUpsertMatchField(collectionMatch, pkCol);
            if (pkCol != null || !nestedKeys.isEmpty() || rowsHaveDottedKeys(flatRows)) {
                // 主键为准；空主键保留空；仅子表/其他字段归并到上一主行
                for (RowGroup group : groupCarryForwardRows(flatRows, nestedKeys, pkCol)) {
                    ObjectNode built = MAPPER.createObjectNode();
                    fillObjectSkippingArrays(elemObj, built, bag, group.parentRow(), nestedKeys);
                    fillNestedArraysForParent(
                            elemObj, built, bag, nestedKeys, primary, group.childRows());
                    backfillParentScalarsFromBag(elemObj, built, bag, nestedKeys);
                    enrichMatchKeyFromBag(built, key, bag);
                    upsert(targetArr, built, matchField);
                }
                coalesceIfToolArray(key, targetArr);
                return;
            }
            for (Map<String, String> row : flatRows) {
                if (isBlankRow(row)) {
                    continue;
                }
                ObjectNode built = MAPPER.createObjectNode();
                fillObject(elemObj, built, bag, row);
                enrichMatchKeyFromBag(built, key, bag);
                upsert(targetArr, built, matchField);
            }
            coalesceIfToolArray(key, targetArr);
            return;
        }

        // 无主表：标量（工序号）+ 本页独立子表（辅助材料/工步）→ 一条，按 group.key upsert
        List<String> nestedKeys = nestedArrayKeys(elemObj);
        boolean nestedHasData = false;
        for (String nk : nestedKeys) {
            JsonNode nestTpl = elemObj.get(nk);
            if (nestTpl != null && nestTpl.isArray()
                    && nestedHasSeparateTable((ArrayNode) nestTpl, bag, null, nk)) {
                nestedHasData = true;
                break;
            }
        }
        if (canResolveAny(elemObj, bag, rowCtx) || nestedHasData) {
            ObjectNode built = MAPPER.createObjectNode();
            fillObjectSkippingArrays(elemObj, built, bag, rowCtx, nestedKeys);
            fillNestedArraysForParent(elemObj, built, bag, nestedKeys, null, null);
            backfillParentScalarsFromBag(elemObj, built, bag, nestedKeys);
            String matchField = upsertFieldForCollection(key);
            enrichMatchKeyFromBag(built, key, bag);
            upsert(targetArr, built, matchField);
        }
        coalesceIfToolArray(key, targetArr);
    }

    /**
     * 行上下文里若带了空的同名字段（如工步行没有技术要求），会把页级标量盖成 ""。
     * 父级占位仍空时，再用袋里的<strong>页级标量</strong>补一次。
     * 绝不对 {@code {{表.列}}} 做整表首行回填——否则空工序号的「来料图」会被填成表里第一个工序号。
     */
    private void backfillParentScalarsFromBag(
            ObjectNode elemObj, ObjectNode built, PageValueBag bag, List<String> nestedKeys) {
        if (elemObj == null || built == null || bag == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = elemObj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (key == null || (nestedKeys != null && nestedKeys.contains(key))) {
                continue;
            }
            JsonNode t = e.getValue();
            if (t == null || !t.isTextual()) {
                continue;
            }
            String tpl = t.asText("");
            Matcher m = PLACEHOLDER.matcher(tpl);
            if (!m.find()) {
                continue;
            }
            String path = m.group(1).trim();
            // 只补 {{技术要求}} 这类页级标量；表.列 留给行上下文
            if (path.isEmpty() || path.indexOf('.') >= 0) {
                continue;
            }
            if (!built.path(key).asText("").isBlank()) {
                continue;
            }
            String fromBag = bag.scalar(path);
            if (fromBag != null && !fromBag.isBlank()) {
                built.put(key, stripNewlines(fromBag));
            }
        }
    }

    /**
     * 嵌套数组两类：① 主表虚线列（设备.名称）；② 本页独立表（辅助材料 / 工步内容）。
     */
    private void fillNestedArraysForParent(
            ObjectNode elemObj,
            ObjectNode built,
            PageValueBag bag,
            List<String> nestedKeys,
            String parentPrimaryTable,
            List<Map<String, String>> childRows) {
        if (nestedKeys == null || nestedKeys.isEmpty()) {
            return;
        }
        for (String nk : nestedKeys) {
            JsonNode nestTpl = elemObj.get(nk);
            if (nestTpl == null || !nestTpl.isArray()) {
                continue;
            }
            ArrayNode arrTpl = (ArrayNode) nestTpl;
            boolean hasDotted = childRows != null && rowsHaveNestedContent(childRows, nk);
            if (hasDotted) {
                fillNestedArrayFromFlatRows(nk, arrTpl, built, bag, childRows);
            } else if (nestedHasSeparateTable(arrTpl, bag, parentPrimaryTable, nk)) {
                fillArray(nk, arrTpl, built, bag, null);
            } else if (childRows != null) {
                fillNestedArrayFromFlatRows(nk, arrTpl, built, bag, childRows);
            } else {
                fillArray(nk, arrTpl, built, bag, null);
            }
        }
    }

    private static boolean rowsHaveNestedContent(
            List<Map<String, String>> rows, String nestedKey) {
        if (rows == null) {
            return false;
        }
        for (Map<String, String> row : rows) {
            if (rowHasNestedContent(row, nestedKey)) {
                return true;
            }
        }
        return false;
    }

    /** 嵌套模板引用了「非父主表」的表，或袋子里直接有与嵌套名同名的表。 */
    private boolean nestedHasSeparateTable(
            ArrayNode nestTpl, PageValueBag bag, String parentPrimaryTable, String nestedKey) {
        if (bag == null) {
            return false;
        }
        if (nestedKey != null && bag.hasTable(nestedKey) && !bag.tableRows(nestedKey).isEmpty()) {
            return true;
        }
        for (String t : referencedTables(nestTpl)) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (parentPrimaryTable != null && parentPrimaryTable.equals(t)) {
                continue;
            }
            if (bag.hasTable(t) && !bag.tableRows(t).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 只收集非嵌套数组字段上的表名，避免把「辅助材料表」当成机械加工的主键表。 */
    private List<String> referencedParentTables(ObjectNode elem, List<String> nestedKeys) {
        List<String> names = new ArrayList<>();
        if (elem == null) {
            return names;
        }
        Iterator<Map.Entry<String, JsonNode>> it = elem.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (nestedKeys != null && nestedKeys.contains(e.getKey())
                    && e.getValue() != null && e.getValue().isArray()) {
                continue;
            }
            collectTables(e.getValue(), names);
        }
        return names;
    }

    private static boolean isBlankRow(Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String v : row.values()) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEffectivelyEmpty(ObjectNode obj) {
        if (obj == null || obj.isEmpty()) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            JsonNode v = it.next().getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isTextual()) {
                if (!v.asText("").isBlank()) {
                    return false;
                }
            } else if (v.isArray()) {
                if (!v.isEmpty()) {
                    return false;
                }
            } else if (v.isObject()) {
                if (!isEffectivelyEmpty((ObjectNode) v)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private boolean canResolveAny(JsonNode node, PageValueBag bag, Map<String, String> rowCtx) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            String t = node.asText();
            Matcher m = PLACEHOLDER.matcher(t);
            if (!m.find()) {
                return false;
            }
            String raw = resolveRaw(m.group(1).trim(), bag, rowCtx);
            return raw != null && !raw.isEmpty();
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode c : node) {
                if (canResolveAny(c, bag, rowCtx)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean rowCtxCoversTable(JsonNode elem, String table, Map<String, String> rowCtx) {
        if (elem == null || table == null || rowCtx == null) {
            return false;
        }
        List<String> cols = new ArrayList<>();
        collectTableColumns(elem, table, cols);
        if (cols.isEmpty()) {
            return false;
        }
        for (String col : cols) {
            if (!rowCtx.containsKey(col)) {
                return false;
            }
        }
        return true;
    }

    private void collectTableColumns(JsonNode node, String table, List<String> cols) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            Matcher m = PLACEHOLDER.matcher(node.asText());
            while (m.find()) {
                String p = m.group(1).trim();
                int dot = p.indexOf('.');
                if (dot > 0 && table.equals(p.substring(0, dot).trim())) {
                    String col = p.substring(dot + 1).trim();
                    if (!col.isEmpty() && !cols.contains(col)) {
                        cols.add(col);
                    }
                }
            }
            return;
        }
        for (JsonNode c : node) {
            collectTableColumns(c, table, cols);
        }
    }

    private List<String> referencedTables(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectTables(node, names);
        return names;
    }

    private void collectTables(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            Matcher m = PLACEHOLDER.matcher(node.asText());
            while (m.find()) {
                String p = m.group(1).trim();
                int dot = p.indexOf('.');
                if (dot > 0) {
                    String table = p.substring(0, dot).trim();
                    if (!table.isEmpty() && !out.contains(table)) {
                        out.add(table);
                    }
                }
            }
            return;
        }
        for (JsonNode c : node) {
            collectTables(c, out);
        }
    }

    private String resolveText(String template, PageValueBag bag, Map<String, String> rowCtx) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        if (!m.find()) {
            // 非占位：仅当目标仍为空时不覆盖；这里返回 null 表示跳过
            return null;
        }
        m.reset();
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            String raw = resolveRaw(m.group(1).trim(), bag, rowCtx);
            if (raw != null) {
                any = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(raw));
            } else {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return any ? sb.toString() : null;
    }

    private String resolveRaw(String path, PageValueBag bag, Map<String, String> rowCtx) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (rowCtx != null) {
            // 整键、表.列、表.子表.列（行上常见为 设备.名称）
            if (rowCtx.containsKey(path)) {
                String v = rowCtx.get(path);
                if (v != null && !v.isBlank()) {
                    return v;
                }
                // 行上有空键时不要盖死页级标量（技术要求等）
                String fromBag = bag.scalar(path);
                if (fromBag != null) {
                    return fromBag;
                }
                return v == null ? "" : v;
            }
            int dot = path.indexOf('.');
            if (dot > 0) {
                String afterFirst = path.substring(dot + 1).trim();
                if (rowCtx.containsKey(afterFirst)) {
                    String v = rowCtx.get(afterFirst);
                    return v == null ? "" : v;
                }
                if ("工步号".equals(afterFirst) && rowCtx.containsKey("步号")) {
                    String v = rowCtx.get("步号");
                    return v == null ? "" : v;
                }
                if ("步号".equals(afterFirst) && rowCtx.containsKey("工步号")) {
                    String v = rowCtx.get("工步号");
                    return v == null ? "" : v;
                }
                String aliased = resolveRowCtxAlias(afterFirst, rowCtx);
                if (aliased != null) {
                    return aliased;
                }
                int lastDot = path.lastIndexOf('.');
                if (lastDot > dot) {
                    String last = path.substring(lastDot + 1).trim();
                    if (rowCtx.containsKey(last)) {
                        String v = rowCtx.get(last);
                        return v == null ? "" : v;
                    }
                    aliased = resolveRowCtxAlias(last, rowCtx);
                    if (aliased != null) {
                        return aliased;
                    }
                }
                // 行上下文：主键/列没有就是没有，禁止整表捞第一格非空值
                return "";
            }
            return bag.scalar(path);
        }
        String fromTable = bag.resolveTableCell(path);
        if (fromTable != null) {
            return fromTable;
        }
        return bag.scalar(path);
    }

    /**
     * 列名同义（定义写 A、表头写 B）。与集合类型无关，只是表头/占位符用词不一致。
     * @return 命中别名时的单元格值；未命中返回 null
     */
    private static String resolveRowCtxAlias(String col, Map<String, String> rowCtx) {
        if (col == null || col.isBlank() || rowCtx == null) {
            return null;
        }
        if ("检测率%".equals(col) && rowCtx.containsKey("检验率%")) {
            String v = rowCtx.get("检验率%");
            return v == null ? "" : v;
        }
        if ("检验率%".equals(col) && rowCtx.containsKey("检测率%")) {
            String v = rowCtx.get("检测率%");
            return v == null ? "" : v;
        }
        if (col.startsWith("设备或工艺装备.")) {
            String alt = "设备或工艺设备." + col.substring("设备或工艺装备.".length());
            if (rowCtx.containsKey(alt)) {
                String v = rowCtx.get(alt);
                return v == null ? "" : v;
            }
        }
        if (col.startsWith("设备或工艺设备.")) {
            String alt = "设备或工艺装备." + col.substring("设备或工艺设备.".length());
            if (rowCtx.containsKey(alt)) {
                String v = rowCtx.get(alt);
                return v == null ? "" : v;
            }
        }
        return null;
    }

    /** 输出定义根上 group[]：{ collection, key }，key 为平铺表列名。 */
    private String groupKeyForCollection(String collectionKey) {
        if (definition == null || collectionKey == null) {
            return null;
        }
        JsonNode groups = definition.get("group");
        if (groups == null || !groups.isArray()) {
            return null;
        }
        for (JsonNode g : groups) {
            if (g == null || !g.isObject()) {
                continue;
            }
            if (collectionKey.equals(text(g.get("collection")))) {
                String key = text(g.get("key"));
                return key == null || key.isBlank() ? null : key.trim();
            }
        }
        return null;
    }

    /**
     * 行分组主键（与集合名无关）：
     * <ol>
     *   <li>{@code group[]} 配置优先（如机械加工 → 工序号）</li>
     *   <li>否则看元素模板上的行号字段：工步号 / 步号 / 序号</li>
     * </ol>
     * 有嵌套数组时这些字段表示「壳下的行」；无嵌套时表示「集合里的每一行」——同一套规则。
     */
    private String resolveGroupKey(String collectionKey, ObjectNode elemObj, List<String> nestedKeys) {
        String configured = groupKeyForCollection(collectionKey);
        if (configured != null) {
            return configured;
        }
        if (elemObj == null) {
            return null;
        }
        for (String candidate : List.of("工步号", "步号", "序号")) {
            if (elemObj.has(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * upsert 匹配键（与集合名无关）：
     * 表内行主键（序号/工步号）与 link 推导的集合键（常为工序号）不一致时，用行主键。
     * 否则多行明细会被压进同一条（同一工序号），只剩最后一行。
     * 机械加工不中招：group/模板壳层主键就是工序号，与 link 一致，且明细在嵌套数组里。
     */
    private static String resolveUpsertMatchField(String collectionMatchField, String rowPkCol) {
        if (rowPkCol != null && !rowPkCol.isBlank()) {
            if (collectionMatchField == null
                    || collectionMatchField.isBlank()
                    || !rowPkCol.equals(collectionMatchField)) {
                return rowPkCol;
            }
        }
        return collectionMatchField;
    }

    /** 工步号/步号/序号：空主键一律续行；工序号：空但有名称则仍是新行。 */
    private static boolean emptyPkMeansContinue(String pkCol) {
        return "工步号".equals(pkCol) || "步号".equals(pkCol) || "序号".equals(pkCol);
    }

    private static String rowPkValue(Map<String, String> row, String pkCol) {
        if (row == null || pkCol == null) {
            return null;
        }
        if (row.containsKey(pkCol)) {
            return row.get(pkCol);
        }
        if ("工步号".equals(pkCol) && row.containsKey("步号")) {
            return row.get("步号");
        }
        if ("步号".equals(pkCol) && row.containsKey("工步号")) {
            return row.get("工步号");
        }
        return null;
    }

    private static List<String> nestedArrayKeys(ObjectNode elem) {
        List<String> keys = new ArrayList<>();
        if (elem == null) {
            return keys;
        }
        Iterator<Map.Entry<String, JsonNode>> it = elem.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue() != null && e.getValue().isArray()) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    private static boolean rowsHaveDottedKeys(List<Map<String, String>> rows) {
        if (rows == null) {
            return false;
        }
        for (Map<String, String> row : rows) {
            if (row == null) {
                continue;
            }
            for (String k : row.keySet()) {
                if (k != null && k.indexOf('.') > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private record RowGroup(Map<String, String> parentRow, List<Map<String, String>> childRows) {
    }

    /**
     * 分组：主键列以本行为准（空就空，不进位）；其他主表列可进位；仅子表列的续行挂上一组。
     */
    static List<RowGroup> groupCarryForwardRows(
            List<Map<String, String>> rows, List<String> nestedKeys, String pkCol) {
        List<RowGroup> groups = new ArrayList<>();
        Map<String, String> carried = new LinkedHashMap<>();
        RowGroup cur = null;
        List<String> nest = nestedKeys == null ? List.of() : nestedKeys;
        for (Map<String, String> row : rows) {
            if (isBlankRow(row)) {
                continue;
            }
            Map<String, String> prevParent = cur != null ? cur.parentRow() : null;
            if (isContinuationRow(row, nest, pkCol, cur != null)) {
                if (cur != null) {
                    cur.childRows().add(row);
                    mergeParentFieldsFromContinuation(cur.parentRow(), row, nest, pkCol);
                } else {
                    cur = new RowGroup(parentSnapshot(row, nest, pkCol, carried), new ArrayList<>());
                    groups.add(cur);
                    cur.childRows().add(row);
                }
                continue;
            }
            Map<String, String> parent = parentSnapshot(row, nest, pkCol, carried);
            // 新主键开行后，进位从本行重新计，避免下一道工序继承上一道名称
            if (pkCol != null && !pkCol.isBlank()) {
                String pk = parent.get(pkCol);
                if (pk != null && !pk.isBlank()) {
                    carried.clear();
                }
            }
            updateCarriedNonPk(carried, parent, pkCol);
            cur = new RowGroup(parent, new ArrayList<>());
            groups.add(cur);
            cur.childRows().add(row);
        }
        return groups;
    }

    /** 兼容旧调用。 */
    static List<RowGroup> groupCarryForwardRows(
            List<Map<String, String>> rows, List<String> nestedKeys) {
        return groupCarryForwardRows(rows, nestedKeys, null);
    }

    /**
     * 主表续行：只看主键——有值新开，无值则合并到上一行；不参考子表列。
     * 工序号空但有工序名称等 → 仍新开（来料图）；工步号空 → 一律续行。
     */
    private static boolean isContinuationRow(
            Map<String, String> row,
            List<String> nestedKeys,
            String pkCol,
            boolean hasPrevious) {
        if (!hasPrevious) {
            return false;
        }
        if (pkCol != null && !pkCol.isBlank()) {
            String pk = rowPkValue(row, pkCol);
            if (pk != null && !pk.isBlank()) {
                return false;
            }
            // 工序号：空主键 + 其它主表字段有值 → 新行；不把子表列算进「其它字段」
            if (!emptyPkMeansContinue(pkCol) && hasNonNestedNonPkContent(row, nestedKeys, pkCol)) {
                return false;
            }
            return true;
        }
        // 未配置主键：仅看主表列是否有锚点（忽略子表虚线列）
        return !hasParentAnchor(row, nestedKeys);
    }

    /** 续行上的主表正文（如工步内容第二行）拼进上一主行，去掉换行。 */
    private static void mergeParentFieldsFromContinuation(
            Map<String, String> parent,
            Map<String, String> row,
            List<String> nestedKeys,
            String pkCol) {
        if (parent == null || row == null) {
            return;
        }
        for (Map.Entry<String, String> e : row.entrySet()) {
            String col = e.getKey();
            if (col == null || isNestedCol(col, nestedKeys)) {
                continue;
            }
            if (pkCol != null && (pkCol.equals(col) || isPkAlias(pkCol, col))) {
                continue;
            }
            String v = stripNewlines(e.getValue());
            if (v.isEmpty()) {
                continue;
            }
            String prev = stripNewlines(parent.get(col));
            if (prev.isEmpty()) {
                parent.put(col, v);
            } else if (!prev.contains(v)) {
                parent.put(col, prev + v);
            }
        }
    }

    static String stripNewlines(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\r", "").replace("\n", "").trim();
    }

    private static boolean hasNonNestedNonPkContent(
            Map<String, String> row, List<String> nestedKeys, String pkCol) {
        for (Map.Entry<String, String> e : row.entrySet()) {
            String col = e.getKey();
            if (col == null || isNestedCol(col, nestedKeys)) {
                continue;
            }
            if (pkCol != null && (pkCol.equals(col) || isPkAlias(pkCol, col))) {
                continue;
            }
            if (e.getValue() != null && !e.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPkAlias(String pkCol, String col) {
        return ("工步号".equals(pkCol) && "步号".equals(col))
                || ("步号".equals(pkCol) && "工步号".equals(col));
    }

    private static Map<String, String> parentSnapshot(
            Map<String, String> row,
            List<String> nestedKeys,
            String pkCol,
            Map<String, String> carried) {
        Map<String, String> parent = new LinkedHashMap<>();
        String rowPk = pkCol == null || pkCol.isBlank() ? null : rowPkValue(row, pkCol);
        boolean freshPk = rowPk != null && !rowPk.isBlank();
        // 其它字段可从上一主行进位；但本行已有新主键时不得继承上一道工序的名称等身份字段
        if (!freshPk && carried != null) {
            for (Map.Entry<String, String> e : carried.entrySet()) {
                if (pkCol != null && (pkCol.equals(e.getKey()) || isPkAlias(pkCol, e.getKey()))) {
                    continue;
                }
                parent.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : row.entrySet()) {
            String col = e.getKey();
            if (col == null || isNestedCol(col, nestedKeys)) {
                continue;
            }
            if (pkCol != null && (pkCol.equals(col) || isPkAlias(pkCol, col))) {
                // 主键：以本行为准；步号/工步号互认，统一写到配置的 pk 名
                String v = e.getValue() == null ? "" : stripNewlines(e.getValue());
                parent.put(pkCol, v);
            } else if (e.getValue() != null && !e.getValue().isBlank()) {
                parent.put(col, stripNewlines(e.getValue()));
            }
        }
        if (pkCol != null && !pkCol.isBlank() && !parent.containsKey(pkCol)) {
            String alt = rowPkValue(row, pkCol);
            parent.put(pkCol, alt == null ? "" : alt);
        }
        return parent;
    }

    private static void updateCarriedNonPk(
            Map<String, String> carried, Map<String, String> parent, String pkCol) {
        for (Map.Entry<String, String> e : parent.entrySet()) {
            if (pkCol != null && pkCol.equals(e.getKey())) {
                continue;
            }
            if (e.getValue() != null && !e.getValue().isBlank()) {
                carried.put(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean hasParentAnchor(Map<String, String> row, List<String> nestedKeys) {
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (isNestedCol(e.getKey(), nestedKeys)) {
                continue;
            }
            if (e.getValue() != null && !e.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否属于「嵌套数组」虚线列（如模板里 {@code 设备: []} 对应 {@code 设备.名称}）。
     * <p>
     * 仅当 {@code nestedKeys} 里真有对应数组名时才算嵌套。
     * 以前 {@code nestedKeys} 为空时把所有带点的列都当成嵌套丢掉——扁平模板里
     * {@code 设备或工艺装备: {}} 是对象不是数组，列进不了 parentRow，子表就全空。
     */
    private static boolean isNestedCol(String col, List<String> nestedKeys) {
        if (col == null || nestedKeys == null || nestedKeys.isEmpty()) {
            return false;
        }
        for (String nk : nestedKeys) {
            if (nk == null || nk.isEmpty()) {
                continue;
            }
            if (col.equals(nk) || col.startsWith(nk + ".")) {
                return true;
            }
        }
        return false;
    }

    private void fillObjectSkippingArrays(
            ObjectNode template,
            ObjectNode target,
            PageValueBag bag,
            Map<String, String> rowCtx,
            List<String> skipArrayKeys) {
        Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            if ("link".equals(key) || "group".equals(key) || "route".equals(key)
                    || "version".equals(key) && e.getValue().isNumber()) {
                if ("version".equals(key) && e.getValue().isNumber()) {
                    target.set(key, e.getValue().deepCopy());
                }
                continue;
            }
            JsonNode t = e.getValue();
            if (t == null || t.isNull()) {
                continue;
            }
            if (t.isTextual()) {
                putResolvedText(target, key, t.asText(), bag, rowCtx);
                continue;
            }
            if (t.isArray()) {
                if (skipArrayKeys != null && skipArrayKeys.contains(key)) {
                    continue;
                }
                fillArray(key, (ArrayNode) t, target, bag, rowCtx);
                continue;
            }
            if (t.isObject()) {
                ObjectNode childTarget = target.has(key) && target.get(key).isObject()
                        ? (ObjectNode) target.get(key)
                        : target.putObject(key);
                fillObjectSkippingArrays((ObjectNode) t, childTarget, bag, rowCtx, skipArrayKeys);
            }
        }
    }

    private void fillNestedArrayFromFlatRows(
            String nestedKey,
            ArrayNode templateArr,
            ObjectNode parent,
            PageValueBag bag,
            List<Map<String, String>> childRows) {
        ArrayNode out = parent.putArray(nestedKey);
        ObjectNode elemTpl = null;
        if (templateArr != null && !templateArr.isEmpty() && templateArr.get(0).isObject()) {
            elemTpl = (ObjectNode) templateArr.get(0);
        }
        for (Map<String, String> child : childRows) {
            if (!rowHasNestedContent(child, nestedKey)) {
                continue;
            }
            ObjectNode built = MAPPER.createObjectNode();
            Map<String, String> ctx = rowCtxForNested(child, nestedKey);
            if (elemTpl != null) {
                fillObject(elemTpl, built, bag, ctx);
            } else {
                String prefix = nestedKey + ".";
                for (Map.Entry<String, String> e : child.entrySet()) {
                    String col = e.getKey();
                    if (col != null && col.startsWith(prefix)
                            && e.getValue() != null && !e.getValue().isBlank()) {
                        built.put(col.substring(prefix.length()), e.getValue());
                    }
                }
            }
            if (!isEffectivelyEmpty(built)) {
                normalizeMultilineName(built);
                out.add(built);
            }
        }
        coalesceSparseSubsetRows(out);
    }

    /**
     * 子表（量具/刀具等）多列续行合并——只针对子表对象数组：
     * <p>
     * 本行除「名称」外的其他列（如代号）全空，且「名称」非空 → 名称并入上一行，不新开对象。
     * <p>
     * 若本行名称像新量具名（含汉字）而其他列又空：视为新对象，不能并进上一件
     * （否则「深度卡尺」会被并进「卡尺…外购」）。仅无「真子集」会犯这个错。
     * 规格续行（{@code 0mm~200mm}，无汉字）才在其他列全空时并入上一行。
     */
    static void coalesceSparseSubsetRows(ArrayNode arr) {
        if (arr == null || arr.size() < 2) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            if (n != null && n.isObject()) {
                normalizeMultilineName((ObjectNode) n);
            }
        }
        for (int i = 1; i < arr.size(); ) {
            JsonNode cur = arr.get(i);
            JsonNode prev = arr.get(i - 1);
            if (cur == null || !cur.isObject() || prev == null || !prev.isObject()) {
                i++;
                continue;
            }
            ObjectNode curObj = (ObjectNode) cur;
            ObjectNode prevObj = (ObjectNode) prev;
            if (!shouldMergeSubTableContinuation(prevObj, curObj)) {
                i++;
                continue;
            }
            java.util.Set<String> curFilled = filledTextKeys(curObj);
            for (String k : curFilled) {
                appendTextField(prevObj, k, curObj.path(k).asText("").trim());
            }
            arr.remove(i);
        }
    }

    /**
     * 子表续行：其他列全空 + 有名称 + 名称是规格段（非新量具汉字名）。
     */
    static boolean shouldMergeSubTableContinuation(ObjectNode prev, ObjectNode cur) {
        if (prev == null || cur == null) {
            return false;
        }
        String curName = stripNewlines(cur.path("名称").asText("")).trim();
        String prevName = stripNewlines(prev.path("名称").asText("")).trim();
        if (curName.isEmpty() || prevName.isEmpty()) {
            return false;
        }
        if (!otherColumnsEmpty(cur, "名称")) {
            return false;
        }
        // 其他列空：前边「名称」合并；排除新量具名（深度卡尺）被并进上一件
        return looksLikeGaugeSpec(curName);
    }

    /** 除 {@code keepKey} 外，对象上其余文本列是否都空。 */
    static boolean otherColumnsEmpty(ObjectNode obj, String keepKey) {
        if (obj == null) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (keepKey != null && keepKey.equals(e.getKey())) {
                continue;
            }
            JsonNode v = e.getValue();
            if (v != null && v.isTextual() && !v.asText("").isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 规格续行（尺寸段），不是新量具名。如 {@code 0mm~200mm}；「深度卡尺」含汉字则否。 */
    static boolean looksLikeGaugeSpec(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String t = stripNewlines(name).trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.codePoints().anyMatch(
                cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return false;
        }
        if (t.matches("(?i).*\\d.*mm.*")) {
            return true;
        }
        if (t.contains("~") && t.matches(".*\\d.*")) {
            return true;
        }
        return t.matches("(?i)^[0-9Øø⌀φ.].+");
    }

    /** @deprecated 用 {@link #coalesceSparseSubsetRows} */
    static void coalesceTrailingSpecs(ArrayNode arr) {
        coalesceSparseSubsetRows(arr);
    }

    private static java.util.Set<String> filledTextKeys(ObjectNode obj) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (obj == null) {
            return keys;
        }
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v != null && v.isTextual() && !v.asText("").isBlank()) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    private static void appendTextField(ObjectNode target, String key, String value) {
        if (target == null || key == null) {
            return;
        }
        String v = stripNewlines(value);
        if (v.isEmpty()) {
            return;
        }
        String prev = stripNewlines(target.path(key).asText(""));
        if (prev.isEmpty()) {
            target.put(key, v);
        } else if (!prev.contains(v)) {
            target.put(key, prev + v);
        }
    }

    /** 名称多行并成一行（去掉换行）。 */
    static void normalizeMultilineName(ObjectNode obj) {
        if (obj == null || !obj.has("名称")) {
            return;
        }
        String name = obj.path("名称").asText("");
        if (name == null || name.isBlank()) {
            return;
        }
        String joined = stripNewlines(name);
        if (!joined.equals(name)) {
            obj.put("名称", joined);
        }
    }

    private static void coalesceIfToolArray(String key, ArrayNode targetArr) {
        // 只合并叶子子表（量具/刀具…），不对工序目录/机械加工/工步等主集合做「字段子集」合并
        if (!isLeafSubTableKey(key)) {
            return;
        }
        if (targetArr != null && !targetArr.isEmpty() && targetArr.get(0).isObject()) {
            coalesceSparseSubsetRows(targetArr);
        }
    }

    private static boolean isLeafSubTableKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if ("工序目录".equals(key) || "机械加工".equals(key) || "检验".equals(key)
                || "辅助".equals(key) || "原料".equals(key) || "工步".equals(key)) {
            return false;
        }
        return true;
    }

    private static Map<String, String> rowCtxForNested(Map<String, String> child, String nestedKey) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (child != null) {
            ctx.putAll(child);
        }
        if (nestedKey == null || nestedKey.isEmpty() || child == null) {
            return ctx;
        }
        String prefix = nestedKey + ".";
        for (Map.Entry<String, String> e : child.entrySet()) {
            String col = e.getKey();
            if (col != null && col.startsWith(prefix)) {
                ctx.put(col.substring(prefix.length()), e.getValue());
            }
        }
        return ctx;
    }

    private static boolean rowHasNestedContent(Map<String, String> row, String nestedKey) {
        if (row == null || nestedKey == null) {
            return false;
        }
        String prefix = nestedKey + ".";
        for (Map.Entry<String, String> e : row.entrySet()) {
            String col = e.getKey();
            if (col == null) {
                continue;
            }
            if ((col.equals(nestedKey) || col.startsWith(prefix))
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String upsertFieldForCollection(String collectionKey) {
        // 优先 group.key（工序号），保证后续页按主键合并
        String groupKey = groupKeyForCollection(collectionKey);
        if (groupKey != null && !groupKey.isBlank()) {
            return groupKey;
        }
        JsonNode links = definition.get("link");
        if (links == null || !links.isArray()) {
            return null;
        }
        for (JsonNode link : links) {
            if (link == null || !link.isObject()) {
                continue;
            }
            String to = text(link.get("to"));
            if (to == null) {
                continue;
            }
            // 机械加工.工序号
            int dot = to.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            if (collectionKey.equals(to.substring(0, dot).trim())) {
                return to.substring(dot + 1).trim();
            }
            // 也认 from 侧：工序目录.工序号
            String from = text(link.get("from"));
            if (from != null) {
                int fdot = from.indexOf('.');
                if (fdot > 0 && collectionKey.equals(from.substring(0, fdot).trim())) {
                    return from.substring(fdot + 1).trim();
                }
            }
        }
        return null;
    }

    private void upsert(ArrayNode arr, ObjectNode built, String matchField) {
        if (built == null || isEffectivelyEmpty(built)) {
            return;
        }
        if (matchField != null && built.has(matchField) && !built.get(matchField).asText("").isEmpty()) {
            String key = built.get(matchField).asText();
            for (int i = 0; i < arr.size(); i++) {
                JsonNode cur = arr.get(i);
                if (cur != null && cur.isObject() && key.equals(cur.path(matchField).asText(""))) {
                    mergeObject((ObjectNode) cur, built);
                    return;
                }
            }
        } else {
            // 无匹配键：内容相同则跳过，避免同页确认两次 / 空行拆成两条
            for (int i = 0; i < arr.size(); i++) {
                JsonNode cur = arr.get(i);
                if (cur != null && cur.isObject() && sameShallowText((ObjectNode) cur, built)) {
                    mergeObject((ObjectNode) cur, built);
                    return;
                }
            }
        }
        arr.add(built);
    }

    private static boolean sameShallowText(ObjectNode a, ObjectNode b) {
        if (a.size() != b.size()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> it = a.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode av = e.getValue();
            JsonNode bv = b.get(e.getKey());
            if (bv == null) {
                return false;
            }
            if (av.isTextual() && bv.isTextual()) {
                if (!av.asText("").equals(bv.asText(""))) {
                    return false;
                }
            } else if (!av.equals(bv)) {
                return false;
            }
        }
        return true;
    }

    private void mergeObject(ObjectNode target, ObjectNode src) {
        Iterator<Map.Entry<String, JsonNode>> it = src.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            // 空串不覆盖已有值（后续页只补有值字段）
            if (v.isTextual() && v.asText().isEmpty()) {
                continue;
            }
            if (v.isObject() && target.has(e.getKey()) && target.get(e.getKey()).isObject()) {
                mergeObject((ObjectNode) target.get(e.getKey()), (ObjectNode) v);
            } else if (v.isArray()) {
                if (v.isEmpty()) {
                    continue;
                }
                // 非空数组：有内容则写入（建壳带设备 / 后续页带工步）
                target.set(e.getKey(), v.deepCopy());
            } else {
                target.set(e.getKey(), v.deepCopy());
            }
        }
    }

    /**
     * 按 route[] 把源集合条目分到目标集合。
     * 同一字段命中多条规则时取<strong>最具体</strong>的一条（正则字面更长优先），
     * 避免 {@code ^\\d+$} 与 {@code ^\\d+Y$} 因顺序/误填源表把 2Y 留在机械加工。
     * 分流后清掉各目标集合里「键值不符合任何指向该集合的规则」的脏壳。
     */
    private void applyRoutes() {
        JsonNode routes = definition.get("route");
        if (routes == null || !routes.isArray() || document == null) {
            return;
        }
        List<RouteRule> rules = new ArrayList<>();
        for (JsonNode r : routes) {
            if (r == null || !r.isObject()) {
                continue;
            }
            String from = text(r.get("from"));
            String field = text(r.get("field"));
            String match = text(r.get("match"));
            String to = text(r.get("to"));
            if (from == null || from.isBlank() || field == null || field.isBlank()
                    || match == null || match.isBlank() || to == null || to.isBlank()) {
                continue;
            }
            try {
                rules.add(new RouteRule(from.trim(), field.trim(), Pattern.compile(match), to.trim()));
            } catch (Exception ignored) {
                // 非法正则跳过
            }
        }
        if (rules.isEmpty()) {
            return;
        }
        Map<String, List<RouteRule>> byFrom = new LinkedHashMap<>();
        for (RouteRule rule : rules) {
            byFrom.computeIfAbsent(rule.from, k -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<RouteRule>> e : byFrom.entrySet()) {
            List<ObjectNode> sources = items(document, e.getKey());
            List<RouteRule> fromRules = e.getValue();
            for (ObjectNode src : sources) {
                if (src == null || isEffectivelyEmpty(src)) {
                    continue;
                }
                RouteRule best = bestMatchingRoute(src, fromRules);
                if (best == null) {
                    continue;
                }
                ArrayNode targetArr = document.has(best.to) && document.get(best.to).isArray()
                        ? (ArrayNode) document.get(best.to)
                        : document.putArray(best.to);
                String matchField = groupKeyForCollection(best.to);
                if (matchField == null || matchField.isBlank()) {
                    matchField = best.field;
                }
                ObjectNode copy = projectForTarget(best.to, src);
                upsert(targetArr, copy, matchField);
            }
        }
        pruneRouteTargets(rules);
    }

    /** 命中规则中选 pattern 字面最长者（更具体）；同分保持定义顺序先出现的。 */
    private static RouteRule bestMatchingRoute(ObjectNode src, List<RouteRule> rules) {
        RouteRule best = null;
        int bestLen = -1;
        for (RouteRule rule : rules) {
            String val = src.path(rule.field).asText("");
            if (val.isBlank() || !rule.pattern.matcher(val).matches()) {
                continue;
            }
            int len = rule.pattern.pattern().length();
            if (best == null || len > bestLen) {
                best = rule;
                bestLen = len;
            }
        }
        return best;
    }

    /**
     * 若集合是某条 route 的 {@code to}，则删掉键值不匹配「任何指向该集合的规则」的条目。
     * 防止目录表被误绑进机械加工后，2Y 等检验号残留在机加数组里。
     */
    private void pruneRouteTargets(List<RouteRule> rules) {
        if (document == null || rules == null || rules.isEmpty()) {
            return;
        }
        Map<String, List<RouteRule>> byTo = new LinkedHashMap<>();
        for (RouteRule rule : rules) {
            byTo.computeIfAbsent(rule.to, k -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<RouteRule>> e : byTo.entrySet()) {
            String collection = e.getKey();
            if (!document.has(collection) || !document.get(collection).isArray()) {
                continue;
            }
            ArrayNode arr = (ArrayNode) document.get(collection);
            List<RouteRule> toRules = e.getValue();
            String field = toRules.get(0).field;
            for (int i = arr.size() - 1; i >= 0; i--) {
                JsonNode n = arr.get(i);
                if (n == null || !n.isObject()) {
                    continue;
                }
                String val = n.path(field).asText("");
                if (val.isBlank()) {
                    continue;
                }
                boolean ok = false;
                for (RouteRule rule : toRules) {
                    if (rule.pattern.matcher(val).matches()) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    arr.remove(i);
                }
            }
        }
    }

    /** 若目标集合在定义里有元素模板，只拷模板出现的字段；否则整对象拷贝。 */
    private ObjectNode projectForTarget(String collection, ObjectNode src) {
        ObjectNode copy = MAPPER.createObjectNode();
        if (definition instanceof ObjectNode defRoot) {
            JsonNode tpl = defRoot.get(collection);
            if (tpl != null && tpl.isArray() && !tpl.isEmpty() && tpl.get(0).isObject()) {
                ObjectNode elemTpl = (ObjectNode) tpl.get(0);
                Iterator<String> names = elemTpl.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    if (src.has(name)) {
                        copy.set(name, src.get(name).deepCopy());
                    } else if (elemTpl.get(name) != null && elemTpl.get(name).isArray()) {
                        copy.putArray(name);
                    } else if (elemTpl.get(name) != null && elemTpl.get(name).isObject()) {
                        copy.putObject(name);
                    } else {
                        copy.put(name, "");
                    }
                }
                return copy;
            }
        }
        return src.deepCopy();
    }

    private record RouteRule(String from, String field, Pattern pattern, String to) {
    }

    private void applyLinks() {
        JsonNode links = definition.get("link");
        if (links == null || !links.isArray() || document == null) {
            return;
        }
        for (JsonNode link : links) {
            if (link == null || !link.isObject()) {
                continue;
            }
            String fromPath = text(link.get("from"));
            String toPath = text(link.get("to"));
            JsonNode set = link.get("set");
            if (fromPath == null || toPath == null || set == null || !set.isObject() || set.isEmpty()) {
                continue;
            }
            PathRef from = PathRef.parse(fromPath);
            PathRef to = PathRef.parse(toPath);
            if (from == null || to == null) {
                continue;
            }
            List<ObjectNode> fromItems = items(document, from.collection);
            List<ObjectNode> toItems = items(document, to.collection);
            for (ObjectNode toItem : toItems) {
                String toKey = toItem.path(to.field).asText("");
                if (toKey.isEmpty()) {
                    continue;
                }
                for (ObjectNode fromItem : fromItems) {
                    if (!toKey.equals(fromItem.path(from.field).asText(""))) {
                        continue;
                    }
                    Iterator<Map.Entry<String, JsonNode>> sets = set.fields();
                    while (sets.hasNext()) {
                        Map.Entry<String, JsonNode> se = sets.next();
                        PathRef dest = PathRef.parse(se.getKey());
                        PathRef src = PathRef.parse(se.getValue().asText(""));
                        if (dest == null || src == null) {
                            continue;
                        }
                        ObjectNode destItem = dest.collection.equals(from.collection) ? fromItem
                                : (dest.collection.equals(to.collection) ? toItem : null);
                        ObjectNode srcItem = src.collection.equals(to.collection) ? toItem
                                : (src.collection.equals(from.collection) ? fromItem : null);
                        if (destItem == null || srcItem == null) {
                            continue;
                        }
                        String val = srcItem.path(src.field).asText(null);
                        if (val != null && !val.isEmpty()) {
                            destItem.put(dest.field, val);
                        }
                    }
                }
            }
        }
    }

    private List<ObjectNode> items(ObjectNode root, String collection) {
        List<ObjectNode> list = new ArrayList<>();
        if (root == null || collection == null) {
            return list;
        }
        JsonNode n = root.get(collection);
        if (n == null) {
            return list;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                if (c != null && c.isObject()) {
                    list.add((ObjectNode) c);
                }
            }
        } else if (n.isObject()) {
            list.add((ObjectNode) n);
        }
        return list;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText(null);
    }

    private void stripPlaceholdersAndClearCollections(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if ("link".equals(k) || "group".equals(k) || "route".equals(k)) {
                    obj.remove(k);
                    continue;
                }
                JsonNode v = obj.get(k);
                if (v != null && v.isArray()) {
                    obj.putArray(k);
                } else if (v != null && v.isObject()) {
                    stripPlaceholdersAndClearCollections(v);
                } else if (v != null && v.isTextual() && PLACEHOLDER.matcher(v.asText()).find()) {
                    obj.put(k, "");
                }
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                stripPlaceholdersAndClearCollections(c);
            }
        }
    }

    private record PathRef(String collection, String field) {
        static PathRef parse(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            int dot = path.indexOf('.');
            if (dot <= 0 || dot >= path.length() - 1) {
                return null;
            }
            return new PathRef(path.substring(0, dot).trim(), path.substring(dot + 1).trim());
        }
    }

    /** 本页可用的标量与表行。 */
    public static final class PageValueBag {
        private final Map<String, String> scalars = new LinkedHashMap<>();
        private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();

        public void putScalar(String key, String value) {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            scalars.put(key.trim(), value);
        }

        public void putTable(String tableName, List<Map<String, String>> rows) {
            if (tableName == null || tableName.isBlank() || rows == null) {
                return;
            }
            tables.put(tableName.trim(), rows);
        }

        public String scalar(String key) {
            return key == null ? null : scalars.get(key.trim());
        }

        public boolean hasTable(String name) {
            return name != null && tables.containsKey(name.trim());
        }

        public List<Map<String, String>> tableRows(String name) {
            List<Map<String, String>> rows = tables.get(name == null ? "" : name.trim());
            return rows == null ? List.of() : rows;
        }

        /** {@code 表.列}：取该表第一行有值的单元格（标量场景）。 */
        public String resolveTableCell(String path) {
            if (path == null) {
                return null;
            }
            int dot = path.indexOf('.');
            if (dot <= 0) {
                return null;
            }
            String table = path.substring(0, dot).trim();
            String col = path.substring(dot + 1).trim();
            List<Map<String, String>> rows = tableRows(table);
            for (Map<String, String> row : rows) {
                String v = row.get(col);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
            return null;
        }
    }
}
