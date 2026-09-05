package com.weavelay.core.model;

import java.util.List;

/**
 * PDF-Extract-Kit 单页提取结果.
 *
 * <p>对应 Python 侧 POST /api/extract 返回的 JSON:
 * <pre>
 * {
 *   "page_info": { "page_no": 1, "width": 1190, "height": 1684 },
 *   "layout_dets": [ { ... }, ... ]
 * }
 * </pre>
 */
public final class PdfExtractPageResult {

    private PageInfo pageInfo;
    private List<LayoutBlock> layoutDets;

    public PdfExtractPageResult() {
    }

    public PdfExtractPageResult(PageInfo pageInfo, List<LayoutBlock> layoutDets) {
        this.pageInfo = pageInfo;
        this.layoutDets = layoutDets;
    }

    // ---- 嵌套类型 ----

    /** 页面元信息 */
    public static final class PageInfo {
        private int pageNo;
        private int width;
        private int height;

        public PageInfo() {}
        public PageInfo(int pageNo, int width, int height) {
            this.pageNo = pageNo; this.width = width; this.height = height;
        }
        public int getPageNo() { return pageNo; }
        public void setPageNo(int v) { this.pageNo = v; }
        public int getWidth() { return width; }
        public void setWidth(int v) { this.width = v; }
        public int getHeight() { return height; }
        public void setHeight(int v) { this.height = v; }
    }

    /** 单个布局块: 文本行 / 标题 / 公式 / 表格 / 图片等 */
    public static final class LayoutBlock {
        /** 类别: text, title, figure, table, formula, formula_number, header, footer, reference, equation_inline, equation_isolated */
        private String categoryType;
        /** 四点坐标 [x1,y1, x2,y2, x3,y3, x4,y4] */
        private double[] poly;
        /** 置信度 0~1 */
        private double score;
        /** OCR 文本 或 LaTeX 字符串 */
        private String text;
        /** 嵌套子块 (表格内部行列) */
        private List<LayoutBlock> children;

        public LayoutBlock() {}

        public LayoutBlock(String categoryType, double[] poly, double score, String text) {
            this.categoryType = categoryType;
            this.poly = poly;
            this.score = score;
            this.text = text;
        }

        // ---- 便捷方法 ----

        /** 最小包围盒 x */
        public double minX() {
            if (poly == null || poly.length < 6) return 0;
            return Math.min(Math.min(poly[0], poly[2]), Math.min(poly[4], poly.length >= 8 ? poly[6] : poly[4]));
        }
        /** 最小包围盒 y */
        public double minY() {
            if (poly == null || poly.length < 6) return 0;
            return Math.min(Math.min(poly[1], poly[3]), Math.min(poly[5], poly.length >= 8 ? poly[7] : poly[5]));
        }
        /** 最大包围盒 x */
        public double maxX() {
            if (poly == null || poly.length < 6) return 0;
            return Math.max(Math.max(poly[0], poly[2]), Math.max(poly[4], poly.length >= 8 ? poly[6] : poly[4]));
        }
        /** 最大包围盒 y */
        public double maxY() {
            if (poly == null || poly.length < 6) return 0;
            return Math.max(Math.max(poly[1], poly[3]), Math.max(poly[5], poly.length >= 8 ? poly[7] : poly[5]));
        }

        /** 中央 x 坐标 */
        public double centerX() { return (minX() + maxX()) / 2.0; }
        /** 中央 y 坐标 */
        public double centerY() { return (minY() + maxY()) / 2.0; }

        /** 是否文本类块 (含标题) */
        public boolean isTextLike() {
            return "text".equals(categoryType) || "title".equals(categoryType)
                    || "header".equals(categoryType) || "footer".equals(categoryType)
                    || "reference".equals(categoryType) || "abstract".equals(categoryType);
        }

        /** 是否公式类块 */
        public boolean isFormula() {
            return categoryType != null && (categoryType.startsWith("equation") || categoryType.equals("formula")
                    || categoryType.equals("formula_number"));
        }

        /** 是否表格块 */
        public boolean isTable() {
            return "table".equals(categoryType);
        }

        /** 是否图片块 */
        public boolean isFigure() {
            return "figure".equals(categoryType);
        }

        /** 转为 OcrRow (用于兼容现有流程) */
        public OcrRow toOcrRow(String streamName) {
            return new OcrRow(
                    streamName,
                    text != null ? text : "",
                    score,
                    minX(), minY(), maxX(), maxY(),
                    categoryType  // metas 存类别
            );
        }

        // ---- getter/setter ----
        public String getCategoryType() { return categoryType; }
        public void setCategoryType(String v) { this.categoryType = v; }
        public double[] getPoly() { return poly; }
        public void setPoly(double[] v) { this.poly = v; }
        public double getScore() { return score; }
        public void setScore(double v) { this.score = v; }
        public String getText() { return text; }
        public void setText(String v) { this.text = v; }
        public List<LayoutBlock> getChildren() { return children; }
        public void setChildren(List<LayoutBlock> v) { this.children = v; }
    }

    // ---- getter/setter ----

    public PageInfo getPageInfo() { return pageInfo; }
    public void setPageInfo(PageInfo v) { this.pageInfo = v; }
    public List<LayoutBlock> getLayoutDets() { return layoutDets; }
    public void setLayoutDets(List<LayoutBlock> v) { this.layoutDets = v; }

    // ---- 便捷方法 ----

    /** 所有文本类块 */
    public List<LayoutBlock> textBlocks() {
        java.util.List<LayoutBlock> list = new java.util.ArrayList<>();
        if (layoutDets == null) return list;
        for (LayoutBlock b : layoutDets) {
            if (b.isTextLike()) list.add(b);
        }
        return list;
    }

    /** 所有公式块 */
    public List<LayoutBlock> formulaBlocks() {
        java.util.List<LayoutBlock> list = new java.util.ArrayList<>();
        if (layoutDets == null) return list;
        for (LayoutBlock b : layoutDets) {
            if (b.isFormula()) list.add(b);
        }
        return list;
    }

    /** 所有表格块 */
    public List<LayoutBlock> tableBlocks() {
        java.util.List<LayoutBlock> list = new java.util.ArrayList<>();
        if (layoutDets == null) return list;
        for (LayoutBlock b : layoutDets) {
            if (b.isTable()) list.add(b);
        }
        return list;
    }

    /** 全量转为 OcrRow 列表 (兼容现有 OCR 流程) */
    public List<OcrRow> toOcrRows(String streamName) {
        java.util.List<OcrRow> rows = new java.util.ArrayList<>();
        if (layoutDets == null) return rows;
        for (LayoutBlock b : layoutDets) {
            OcrRow row = b.toOcrRow(streamName);
            if (row.getFeature() != null && !row.getFeature().trim().isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 总块数 */
    public int blockCount() {
        return layoutDets != null ? layoutDets.size() : 0;
    }
}
