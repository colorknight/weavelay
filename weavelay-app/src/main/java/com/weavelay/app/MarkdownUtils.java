package com.weavelay.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Markdown 表格生成 / HTML 转换工具（表格弹窗用本地 KaTeX 渲染公式）。
 */
public final class MarkdownUtils {

    private static final String KATEX_RES = "/web/katex/";
    private static Path katexDir;

    private MarkdownUtils() {}

    /**
     * 把表格网格转成 Markdown 表格字符串。
     */
    public static String gridToMarkdown(String[][] grid, int maxRow, int maxCol, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append("\n\n");
        for (int r = 0; r < maxRow; r++) {
            sb.append("|");
            for (int c = 0; c < maxCol; c++) {
                String v = "";
                if (grid[r][c] != null) {
                    // 保留单元格内换行（工序续行），用 <br> 避免拆坏 MD 表格行
                    v = grid[r][c].replace("\r\n", "\n").replace('\r', '\n')
                            .replace("\n", "<br>")
                            .replace("|", "\\|")
                            .replaceAll("[ \\t]{2,}", " ")
                            .trim();
                }
                sb.append(" ").append(v).append(" |");
            }
            sb.append("\n");
            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < maxCol; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 简易 Markdown → HTML，支持标题、表格，以及 $...$ / $$...$$ LaTeX。
     * 使用本地 KaTeX（需配合 {@link #writePopupHtml(String, Path)} 用 file:// 加载）。
     */
    public static String markdownToHtml(String md) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\">");
        html.append("<link rel=\"stylesheet\" href=\"katex/katex.min.css\">");
        html.append("<script src=\"katex/katex.min.js\"></script>");
        html.append("<script src=\"katex/auto-render.min.js\"></script>");
        html.append("<style>");
        html.append("body{font-family:'Microsoft YaHei',sans-serif;font-size:13px;");
        html.append("margin:16px;color:#3a5555;background:#f3f8f8;}");
        html.append("h3{font-size:16px;margin:0 0 12px;color:#2a8f85;}");
        html.append("table{border-collapse:collapse;width:100%;background:#fff;}");
        html.append("th{background:#2a8f85;color:#fff;padding:6px 10px;");
        html.append("text-align:left;font-weight:bold;border:1px solid #238078;}");
        html.append("td{padding:4px 10px;border:1px solid #c5dede;vertical-align:middle;}");
        // 主色绿相间，不用灰色
        html.append("tr:nth-child(even){background:#d9e9e9;}");
        html.append("tr:nth-child(odd){background:#ffffff;}");
        html.append(".katex{font-size:1.05em;}");
        html.append("</style></head><body>\n");

        boolean inTable = false;
        for (String line : md.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (inTable) { html.append("</tbody></table>\n"); inTable = false; }
                html.append("<h3>").append(escHtml(trimmed.substring(4))).append("</h3>\n");
            } else if (trimmed.startsWith("|")) {
                if (!inTable) {
                    html.append("<table><thead>\n");
                    inTable = true;
                    html.append("<tr>");
                    String inner = trimmed.substring(1, trimmed.length() - 1);
                    for (String cell : inner.split("\\|")) {
                        String v = cell.trim();
                        html.append("<th>").append(v.isEmpty() ? "&nbsp;" : escHtml(v)).append("</th>");
                    }
                    html.append("</tr>\n</thead><tbody>\n");
                } else {
                    if (isMarkdownSeparatorRow(trimmed)) continue;
                    html.append("<tr>");
                    String inner = trimmed.substring(1, trimmed.length() - 1);
                    for (String cell : inner.split("\\|")) {
                        String v = cell.trim();
                        html.append("<td>").append(v.isEmpty() ? "&nbsp;" : escHtml(v)).append("</td>");
                    }
                    html.append("</tr>\n");
                }
            }
        }
        if (inTable) { html.append("</tbody></table>\n"); }
        html.append("<script>");
        html.append("if(typeof renderMathInElement==='function'){");
        html.append("renderMathInElement(document.body,{");
        html.append("delimiters:[");
        html.append("{left:'$$',right:'$$',display:true},");
        html.append("{left:'$',right:'$',display:false}");
        html.append("],throwOnError:false});");
        html.append("}");
        html.append("</script>");
        html.append("</body></html>");
        return html.toString();
    }

    private static boolean isMarkdownSeparatorRow(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.contains("|") && t.contains("-") && t.replaceAll("[|\\s\\-:]", "").isEmpty();
    }

    /**
     * 写出 HTML + 本地 KaTeX 资源到临时目录，返回 index.html 路径，供 WebView file:// 加载。
     */
    public static Path writePopupHtml(String html) throws IOException {
        Path dir = Files.createTempDirectory("weavelay-table-");
        ensureKatexExtracted();
        Path katexTarget = dir.resolve("katex");
        Files.createDirectories(katexTarget);
        try (Stream<Path> stream = Files.list(katexDir)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(src)) {
                    Files.copy(src, katexTarget.resolve(src.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path index = dir.resolve("index.html");
        Files.writeString(index, html, StandardCharsets.UTF_8);
        return index;
    }

    private static synchronized void ensureKatexExtracted() throws IOException {
        if (katexDir != null && Files.isDirectory(katexDir)) {
            return;
        }
        Path dir = Files.createTempDirectory("weavelay-katex-");
        String[] files = {
                "katex.min.css", "katex.min.js", "auto-render.min.js",
                "fonts_KaTeX_AMS-Regular.woff2",
                "fonts_KaTeX_Caligraphic-Bold.woff2", "fonts_KaTeX_Caligraphic-Regular.woff2",
                "fonts_KaTeX_Fraktur-Bold.woff2", "fonts_KaTeX_Fraktur-Regular.woff2",
                "fonts_KaTeX_Main-Bold.woff2", "fonts_KaTeX_Main-BoldItalic.woff2",
                "fonts_KaTeX_Main-Italic.woff2", "fonts_KaTeX_Main-Regular.woff2",
                "fonts_KaTeX_Math-BoldItalic.woff2", "fonts_KaTeX_Math-Italic.woff2",
                "fonts_KaTeX_SansSerif-Bold.woff2", "fonts_KaTeX_SansSerif-Italic.woff2",
                "fonts_KaTeX_SansSerif-Regular.woff2", "fonts_KaTeX_Script-Regular.woff2",
                "fonts_KaTeX_Size1-Regular.woff2", "fonts_KaTeX_Size2-Regular.woff2",
                "fonts_KaTeX_Size3-Regular.woff2", "fonts_KaTeX_Size4-Regular.woff2",
                "fonts_KaTeX_Typewriter-Regular.woff2"
        };
        for (String name : files) {
            try (InputStream in = MarkdownUtils.class.getResourceAsStream(KATEX_RES + name)) {
                if (in == null) {
                    continue;
                }
                Path out = dir.resolve(name);
                try (OutputStream os = Files.newOutputStream(out)) {
                    in.transferTo(os);
                }
            }
        }
        katexDir = dir;
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
