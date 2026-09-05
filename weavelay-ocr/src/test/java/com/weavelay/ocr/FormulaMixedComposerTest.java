package com.weavelay.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaMixedComposerTest {

    @Test
    void mergePreservesTrailingProcessLine() {
        String orig = "车⌀148至77处\n完成R2,全长≥57。";
        String mixed = "车$\\varnothing148_{0}^{+0.3}$至77处，⌀134";
        String out = FormulaMixedComposer.mergePreservingPlainTail(orig, mixed);
        assertTrue(out.contains("完成R2"));
        assertTrue(out.contains("全长"));
        assertEquals(2, out.split("\\R").length);
    }

    @Test
    void mergeDoesNotAppendDimensionOcrDuplicate() {
        String orig = "车148+0至77处，134+0，11长6，";
        String mixed = "车⌀148_{0}^{+0.3}至77处，⌀134_{0}^{+0.3}⌀11长6,";
        String out = FormulaMixedComposer.mergePreservingPlainTail(orig, mixed);
        assertEquals(mixed, out);
        assertFalse(out.contains("车148+0"));
    }

    @Test
    void dimensionResidueDetected() {
        assertTrue(FormulaMixedComposer.isDimensionResidueLine("车148+0至77处，134+0，11长6，"));
        assertFalse(FormulaMixedComposer.isDimensionResidueLine("完成R2,全长≥57。"));
        // 夹缝工序字绝不能当残片丢掉
        assertFalse(FormulaMixedComposer.isDimensionResidueLine("至77处"));
        assertFalse(FormulaMixedComposer.isDimensionResidueLine("至77处，"));
        assertTrue(FormulaMixedComposer.isProcessConnectorOnly("至77处，"));
    }

    @Test
    void interleavePlainReplacesDigitRunsWithFormulas() {
        var f1 = new FormulaMixedComposer.FormulaPiece(0, 0, 10, 10, "\\varnothing 1 4 8", false);
        var f2 = new FormulaMixedComposer.FormulaPiece(40, 0, 10, 10, "\\varnothing 1 3 4", false);
        String out = FormulaMixedComposer.interleavePlainWithFormulas("车148+0至134+0", java.util.List.of(f1, f2));
        assertTrue(out != null && out.contains("车"));
        assertTrue(out.contains("至"));
        assertTrue(out.contains("\\varnothing"));
        assertFalse(out.contains("148+0"));
    }

    @Test
    void interleaveKeepsZhi77ChuBetweenFormulas() {
        var f1 = new FormulaMixedComposer.FormulaPiece(0, 0, 10, 10, "\\varnothing148_{0}^{+0.3}", false);
        var f2 = new FormulaMixedComposer.FormulaPiece(80, 0, 10, 10, "\\varnothing134_{0}^{+0.3}", false);
        var f3 = new FormulaMixedComposer.FormulaPiece(160, 0, 10, 10, "\\varnothing11", false);
        String plain = "车148+0至77处，134+0，11长6，";
        String out = FormulaMixedComposer.interleavePlainWithFormulas(plain, java.util.List.of(f1, f2, f3));
        assertTrue(out != null, "交叉拼接应对上 3 个尺寸槽");
        assertTrue(out.contains("至77处"), "中间「至77处」不能丢: " + out);
        assertTrue(out.contains("车"));
        assertTrue(out.contains("\\varnothing148"));
        assertTrue(out.contains("\\varnothing134"));
        assertFalse(out.contains("148+0"));
    }

    @Test
    void interleaveDoesNotSwapShortDiameterAheadOfTallTolerance() {
        // 带公差框更高 → centerY 偏下；矮 Ø11 中心更靠上。旧逻辑按 centerY 会把 Ø11 插到最前。
        var o148 = new FormulaMixedComposer.FormulaPiece(10, 0, 50, 40, "\\varnothing148_{0}^{+0.3}", false);
        var o134 = new FormulaMixedComposer.FormulaPiece(100, 2, 50, 36, "\\varnothing134_{0}^{+0.3}", false);
        var o11 = new FormulaMixedComposer.FormulaPiece(200, 8, 25, 16, "\\varnothing11", false);
        String plain = "车148+0至77处，134+0，11长6，";
        String out = FormulaMixedComposer.interleavePlainWithFormulas(plain, java.util.List.of(o148, o134, o11));
        assertTrue(out != null, "交叉拼接应对上: " + out);
        int i148 = out.indexOf("\\varnothing148");
        int i134 = out.indexOf("\\varnothing134");
        int i11 = out.indexOf("\\varnothing11");
        assertTrue(i148 >= 0 && i134 > i148 && i11 > i134,
                "应为 Ø148→Ø134→Ø11，实际: " + out);
        assertTrue(out.indexOf("至77处") > i148, "「至77处」应跟在 Ø148 后: " + out);
    }

    @Test
    void sortFormulasReadingOrderBandsSameLineByX() {
        var o148 = new FormulaMixedComposer.FormulaPiece(10, 0, 50, 40, "a", false);
        var o134 = new FormulaMixedComposer.FormulaPiece(100, 2, 50, 36, "b", false);
        var o11 = new FormulaMixedComposer.FormulaPiece(200, 8, 25, 16, "c", false);
        var ordered = FormulaMixedComposer.sortFormulasReadingOrder(
                java.util.List.of(o11, o134, o148));
        assertEquals(java.util.List.of(o148, o134, o11), ordered);
    }

    @Test
    void splitCoalescesZhi77ChuAndChangLength() {
        var parts = FormulaMixedComposer.splitPlainProcessParts("车148+0至77处，134+0，11长6，");
        assertTrue(parts.contains("至77处，") || parts.stream().anyMatch(p -> p.contains("至77处")));
        long digitSlots = parts.stream().filter(p -> {
            // 反射不到 private isDigitishRun；用含数字且非夹缝近似
            if (FormulaMixedComposer.isProcessConnectorOnly(p)) {
                return false;
            }
            return p.chars().anyMatch(Character::isDigit);
        }).count();
        assertEquals(3, digitSlots, "parts=" + parts);
    }
}
