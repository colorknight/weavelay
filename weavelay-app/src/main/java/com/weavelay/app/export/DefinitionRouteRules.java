package com.weavelay.app.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weavelay.core.store.TemplateRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从文件类型「输出定义 JSON」的 {@code route[]} 解析 Excel target Sheet 写入/跳转规则。
 * 与 {@link com.weavelay.core.output.OutputJsonEngine} 确认 JSON 时用的正则一致。
 * <p>
 * {@code to} 只服务 JSON 分流；Excel Sheet 名取 {@code target}（旧字段 {@code template} 作别名），
 * 二者皆空则不参与 Excel 写出。
 */
public final class DefinitionRouteRules {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DefinitionRouteRules() {}

    public static List<TemplateRule> parse(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(definitionJson.trim());
            JsonNode routes = root.get("route");
            if (routes == null || !routes.isArray()) {
                return List.of();
            }
            List<TemplateRule> rules = new ArrayList<>();
            int order = 0;
            for (JsonNode r : routes) {
                if (r == null || !r.isObject()) {
                    continue;
                }
                String match = text(r.get("match"));
                if (match.isBlank()) {
                    continue;
                }
                String target = text(r.get("target"));
                if (target.isBlank()) {
                    target = text(r.get("template"));
                }
                if (target.isBlank()) {
                    // 仅 JSON 分流（无 target）——不进 Excel 规则表
                    continue;
                }
                rules.add(new TemplateRule(target, match, order++));
            }
            // 与 OutputJsonEngine 一致：更具体的正则优先匹配
            rules.sort(Comparator.comparingInt((TemplateRule rule) -> rule.getRegex().length()).reversed());
            return rules;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }
}
