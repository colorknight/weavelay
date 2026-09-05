package com.weavelay.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.weavelay.core.store.FamilyRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 输出定义：本地 JSON 编辑器。
 * <p>
 * 自由 JSON（可用 {@code {{字段}}} 占位）。格式化只整理缩进；语法状态只在编辑器底部一行提示。
 */
final class FamilyOutputPane {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String TEMPLATE_SAMPLE = """
            {
              "version": 2,
              "零部件名称": "{{零部件名称}}",
              "零部件代号": "{{零部件代号}}",
              "产品代号": "{{产品代号}}",
              "关重件标识": "{{关重件标识}}",
              "原料": []
            }
            """;

    private static final String HELP_TEXT = """
            这份 JSON 是「这一类文件」确认后要写成的样子。打开定义就能看出将来填什么；各页点确认，才按这里写入实例。Excel 再从实例投影，不单独当主数据。

            一、怎么填
            键是实例里的字段名。值写成 {{占位符}}，确认时用本页采到的同名项替换。没有采到就留空，不会把花括号原文写进实例。
            失焦自动保存。「格式化」只整理缩进，不改含义。

            二、普通字段（标量）
            占位符要和页面类型里的字段名一致：

              "产品代号": "{{产品代号}}",
              "零部件名称": "{{零部件名称}}"

            三、表格（数组）
            用一个对象当行模板，外面套 []。占位符写成 {{表名.列名}}，表名对应页面类型里的表格字段（如「工序目录表」）：

              "工序目录": [
                {
                  "工序号": "{{工序目录表.工序号}}",
                  "工序名称": "{{工序目录表.工序名称}}"
                }
              ]

            嵌套子表用带点的列名，例如页面上采到「设备.名称」：

              "设备": [
                {
                  "名称": "{{工序目录表.设备.名称}}",
                  "型号": "{{工序目录表.设备.型号}}"
                }
              ]

            同一工序多行设备时，空工序号的行会并进上一道工序。

            四、跨页合并：group
            根上的 group 不出现在实例里。指定集合用哪一列当主键，后面的机加卡、检验卡按这个键补到同一条，不重复建条：

              "group": [
                { "collection": "工序目录", "key": "工序号" },
                { "collection": "机械加工", "key": "工序号" }
              ]

            五、按规则分流：route
            把目录里的条目拷到别的集合。match 是整段匹配的正则（JSON 里反斜杠要写成 \\\\）：

              "route": [
                { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工", "target": "机加工序工步" },
                { "from": "工序目录", "field": "工序号", "match": "^\\\\d+Y$", "to": "检验", "target": "检验工序工步" }
              ]

            from / field / match / to 用于确认时分流。target（或旧名 template）只给写出 Excel 用，必须等于模板里已有工作表的全名。
            同一 target 的工序合并进同一张表，目录超链接也跳到这张表，不再按工序号克隆子页。
            没有 target 的条目只进 JSON，目录不加链接。多条都能匹配时，正则字面更长的优先（所以 2Y 进检验，不进机加）。

            六、回填：link
            确认后按匹配键把一侧的字段抄到另一侧：

              "link": [
                {
                  "from": "工序目录.工序号",
                  "to": "机械加工.工序号",
                  "set": { "工序目录.注": "机械加工.技术要求" }
                }
              ]

            七、不要当数据字段的键
            version、group、route、link 是规则，不会写进实例 JSON。version 建议保留为 2。

            八、写出 Excel 怎么映射
            应用模式点「写出Excel」：复制该文件类型绑定的 .xlsx，用累计 JSON 填占位符，写到设置 → 导出 目录，文件名 {文件类型}-{PDF名}.xlsx。
            不按 Sheet 名猜数据，只扫格子里的 {{占位符}}。

            格内占位：
              {{产品代号}}                    根字段
              {{材料表,2,零件重量kg}}         表第 2 行该列（行号从 1 起）
              {{工序目录表}}                  从这格往下展开整表
              {{+工序目录表,工序号,工序名称}} 展开目录；用工序号跑 route，匹配列和命名列加跳到 target 表的链接

            JSON 集合会注册成表；名字不带「表」的再注册一份「名+表」（工序目录 → 工序目录表）。原料同时叫材料表。
            设备这类一层子数组会摊成「设备.名称」列，多条用中文分号拼接。工步等更深的数组目前不会自动拆成 Excel 行。

            当前已接上的工序号规则（match → JSON 集合 / Excel 表）：
              ^\\d+$        1、10     → 机械加工 / 机加工序工步
              ^\\d+Y$       1Y、2Y    → 检验 / 检验工序工步
              ^[FW]\\d+$    F1、W2    → 辅助 / 无 target（只分流 JSON，目录不链接）

            检测、冲压、感应加热、锻造：程序没有内置字头，QJ 903 等标准是按专业分卡片格式，Excel 里通常各有一张工步表。
            要接进来：输出定义加集合和 group，route 写 match + to + target（target 与模板 Sheet 全名一致），并做对应页面类型。
            工序号字头以你们目录为准。厂里常见（需对照目录核实，对不上就链不上）：
              检测    数字+J 或 J+数字
              冲压    C+数字
              锻造    D+数字
              感应加热 / 热处理    R+数字 或 G+数字

            九、和页面类型的关系
            先在页面类型里画圈、起字段名和表名；这里的 {{字段}} / {{表.列}} 必须对得上那些名字，确认才能装进去。
            """;

    private final BorderPane root = new BorderPane();
    private final SettingsWindow window;
    private final Label title = new Label();
    private final JsonEditorWebPane jsonEditor = new JsonEditorWebPane();
    private FamilyRecord currentFamily;
    private boolean suppressSave;

    FamilyOutputPane(SettingsWindow window) {
        this.window = window;
        root.setPadding(new Insets(12));
        root.getStyleClass().add("settings-root");

        title.getStyleClass().add("settings-section-title");

        Button back = AppIcons.iconButton(AppIcons.Kind.BACK, "返回文件类型", true);
        back.setOnAction(e -> {
            save();
            window.showFamilyManage();
        });

        Button formatBtn = new Button("格式化");
        formatBtn.getStyleClass().add("app-btn");
        formatBtn.setOnAction(e -> formatJson());
        Button helpBtn = new Button("说明");
        helpBtn.getStyleClass().add("app-btn");
        helpBtn.setOnAction(e -> SettingsDialogs.showHelp(
                window.getWindow(), "输出定义编写说明", HELP_TEXT));

        HBox topLeft = new HBox(10, back, title);
        topLeft.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, topLeft, spacer, helpBtn, formatBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox editorCard = new VBox(jsonEditor);
        editorCard.getStyleClass().add("settings-output-card");
        VBox.setVgrow(jsonEditor, Priority.ALWAYS);

        jsonEditor.setOnBlur(this::save);

        VBox box = new VBox(12, toolbar, editorCard);
        VBox.setVgrow(editorCard, Priority.ALWAYS);
        root.setCenter(box);
    }

    BorderPane getRoot() {
        return root;
    }

    void editFamily(FamilyRecord family) {
        this.currentFamily = family;
        title.setText("输出定义 · " + family.getName() + " / " + family.getEnglishName());
        suppressSave = true;
        try {
            String json = window.getRuntime().getDatabase()
                    .getFamilyOutputDefinitionJson(family.getCode());
            if (json == null || json.isBlank()) {
                jsonEditor.setText(TEMPLATE_SAMPLE.trim() + "\n");
            } else {
                jsonEditor.setText(prettyOrRaw(json));
            }
        } catch (Exception ex) {
            SettingsWindow.showError(window.getWindow(), "加载输出定义失败", ex);
            jsonEditor.setText("");
        } finally {
            suppressSave = false;
        }
    }

    void reloadFamilies() {
        // no-op
    }

    private void formatJson() {
        if (!jsonEditor.isReady()) {
            return;
        }
        String text = jsonEditor.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            jsonEditor.setText(pretty(text));
            save();
        } catch (Exception ex) {
            // 编辑器内再试一遍（同样禁止 [{ 同行）
            jsonEditor.formatDocument();
        }
    }

    private void save() {
        if (suppressSave || currentFamily == null || !jsonEditor.isReady()) {
            return;
        }
        String text = jsonEditor.getText();
        try {
            window.getRuntime().getDatabase()
                    .saveFamilyOutputDefinitionJson(currentFamily.getCode(), text == null ? "" : text);
            window.notifySaved();
        } catch (Exception ex) {
            SettingsWindow.showError(window.getWindow(), "保存输出定义失败", ex);
        }
    }

    private static String prettyOrRaw(String json) {
        try {
            return pretty(json);
        } catch (Exception ignore) {
            return json;
        }
    }

    /** 复杂值换行后开括号与 key 同列；避免 {@code "k": [ {}]}。 */
    private static String pretty(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return prettyNode(node, 0) + "\n";
    }

    private static boolean isNonEmptyComplex(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return false;
        }
        if (node.isArray() || node.isObject()) {
            return !node.isEmpty();
        }
        return false;
    }

    private static String prettyNode(JsonNode node, int depth) {
        String pad = "  ".repeat(depth);
        String pad1 = "  ".repeat(depth + 1);
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.toString();
        }
        if (node.isTextual()) {
            try {
                return MAPPER.writeValueAsString(node.asText());
            } catch (Exception e) {
                return "\"\"";
            }
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
            java.util.List<java.util.Map.Entry<String, JsonNode>> fields = new java.util.ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            for (int i = 0; i < fields.size(); i++) {
                var e = fields.get(i);
                String key;
                try {
                    key = MAPPER.writeValueAsString(e.getKey());
                } catch (Exception ex) {
                    key = "\"" + e.getKey() + "\"";
                }
                JsonNode child = e.getValue();
                String rendered = prettyNode(child, depth + 1);
                if (isNonEmptyComplex(child)) {
                    sb.append(pad1).append(key).append(":\n").append(pad1).append(rendered);
                } else {
                    sb.append(pad1).append(key).append(": ").append(rendered);
                }
                if (i < fields.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(pad).append('}');
            return sb.toString();
        }
        return node.toString();
    }
}
