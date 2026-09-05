package com.weavelay.core.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LatexToPlainTextTest {

    @Test
    void diameterWithToleranceLinear() {
        String plain = LatexToPlainText.convert("$\\varnothing 134_{0}^{+0.3}$");
        assertFalse(plain.contains("\\"));
        assertFalse(plain.contains("$"));
        assertEquals("⌀134_{0}^{+0.3}", plain);
    }

    @Test
    void mixedTextKeepsPrefixAndOrder() {
        String in = "车$\\varnothing 134_{0}^{+0.3}$至$\\varnothing 1 1$长6";
        assertEquals("车⌀134_{0}^{+0.3}至⌀11长6", LatexToPlainText.convert(in));
    }

    @Test
    void spacedBracesDaggerAndPmAsPlus() {
        String in = "至77处，$\\varnothing 1 3 4 _ { \\, 0 } ^ { \\dagger 0 . 3 }$$\\varnothing 1 1$长6";
        assertEquals("至77处，⌀134_{0}^{+0.3}⌀11长6", LatexToPlainText.convert(in));
        // 上标 \pm 当作公差 +
        assertEquals("⌀134_{0}^{+0.3}",
                LatexToPlainText.convert("$\\varnothing134_{0}^{\\pm0.3}$"));
    }

    @Test
    void unicodeScriptsBecomeLinear() {
        String in = "车⌀148₀⁺⁰·³至77处，⌀134₀±⁰·³，⌀11长6,";
        assertEquals("车⌀148_{0}^{+0.3}至77处，⌀134_{0}^{+0.3}，⌀11长6,",
                LatexToPlainText.convert(in));
    }

    @Test
    void markdownTableCellsStayInPlace() {
        String md = "|工序内容|\n|---|\n|车$\\varnothing134_{0}^{+0.3}$至$\\varnothing11$长6|\n";
        assertEquals("|工序内容|\n|---|\n|车⌀134_{0}^{+0.3}至⌀11长6|\n",
                LatexToPlainText.convert(md));
    }

    @Test
    void markdownPipesNotEatenByDiameterBarOne() {
        // 旧逻辑把 ⌀1| 收成 ⌀11，会吞掉 Markdown 列分隔符，整表错位变空
        String md = "|工序号|工序名称|\n|---|---|\n|检⌀1|下料|\n";
        String out = LatexToPlainText.convert(md);
        assertEquals(md, out);
        assertEquals(2, out.lines().filter(l -> l.startsWith("|检")).findFirst().orElse("").split("\\|", -1).length - 2);
    }

    @Test
    void plainUnchanged() {
        assertEquals("普通车床", LatexToPlainText.convert("普通车床"));
    }

    @Test
    void mathrmMm() {
        assertEquals("0mm～200mm", LatexToPlainText.convert("0\\mathrm{mm}\\sim200\\mathrm{mm}"));
    }

    @Test
    void nestedBracesAndBarOneFlattened() {
        String in = "车$\\varnothing148_{0}^{{{0.3}}}$至77处，$\\varnothing134_{0}^{+0.3}$$\\varnothing[1]$长6";
        assertEquals("车⌀148_{0}^{+0.3}至77处，⌀134_{0}^{+0.3}⌀11长6",
                LatexToPlainText.convert(in));
        assertEquals("⌀11", LatexToPlainText.convert("$\\varnothing|1$"));
        assertEquals("^{0.3}", LatexToPlainText.flattenRedundantBraces("^{{{{0.3}}}}"));
    }

    @Test
    void alphaDiameterGlyphBecomesDiameter() {
        assertEquals("⌀13_{0}^{+0.3}", LatexToPlainText.convert("$\\alpha13_{0}^{+0.3}$"));
        assertEquals("⌀13_{0}^{+0.3}", LatexToPlainText.convert("α13_{0}^{+0.3}"));
        assertEquals("车⌀14_{0}^{+0.3},⌀13_{0}^{+0.3} 长1，全长5，完成R1。",
                LatexToPlainText.convert("车⌀14_{0}^{+0.3},α13_{0}^{+0.3} 长1，全长5，完成R1。"));
        assertEquals("车⌀14_{0}^{+0.3},⌀13_{0}^{+0.3}",
                LatexToPlainText.convert("车$\\varnothing14_{0}^{+0.3}$,$\\alpha13_{0}^{+0.3}$"));
    }

    @Test
    void excelUsesUnicodeScripts() {
        assertEquals("⌀134₀⁺⁰·³",
                LatexToPlainText.toExcelText("$\\varnothing 134_{0}^{+0.3}$"));
        assertEquals("车⌀134₀⁺⁰·³至⌀11长6",
                LatexToPlainText.toExcelText("车$\\varnothing 134_{0}^{+0.3}$至$\\varnothing 1 1$长6"));
        assertEquals("⌀18₀⁺⁰·³",
                LatexToPlainText.toExcelText("\\otimes18 _{0} ^{+0.3}"));
    }
}
