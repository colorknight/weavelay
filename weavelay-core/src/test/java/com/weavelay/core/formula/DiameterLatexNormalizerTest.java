package com.weavelay.core.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiameterLatexNormalizerTest {

    private static String bs(String body) {
        return "\\" + body;
    }

    @Test
    void stripsJunkPrefixBeforeDigitsTolerance() {
        String in = bs("underline { { ") + bs("hat { ") + bs("Delta } } } ")
                + bs("mathfrak { g } 1 8 _ { 0 } ^ { + 0 . 3 }");
        String out = DiameterLatexNormalizer.normalize(in);
        assertEquals(bs("varnothing 1 8 _ { 0 } ^ { + 0 . 3 }"), out);
    }

    @Test
    void mapsBetaLnToDiameterEleven() {
        String in = bs("beta ") + bs("mathrm { ") + bs("ln }");
        assertEquals(bs("varnothing 1 1"), DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void dropsExtraLeadingDigitBeforeThreeDigitTol() {
        String in = bs("varnothing 8 1 3 4 _ { 0 } ^ { + 0 . 3 }");
        assertEquals(bs("varnothing 1 3 4 _ { 0 } ^ { + 0 . 3 }"),
                DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void mapsRightBracketOneToDiameterEleven() {
        String in = bs("varnothing ") + bs("right[ 1");
        assertEquals(bs("varnothing 1 1"), DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void mapsLeftRightBarOneToDiameterEleven() {
        String in = bs("left. ") + bs("varnothing ") + bs("right| 1");
        assertEquals(bs("varnothing 1 1"), DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void mapsBetaAlias() {
        String in = bs("beta 1 4 8 _ { 0 } ^ { + 0 . 3 }");
        assertEquals(bs("varnothing 1 4 8 _ { 0 } ^ { + 0 . 3 }"),
                DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void mapsOtimesAlias() {
        String in = bs("otimes 1 8 _ { 0 } ^ { + 0 . 3 }");
        assertEquals(bs("varnothing 1 8 _ { 0 } ^ { + 0 . 3 }"),
                DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void prependsBareDigitsTolerance() {
        String in = "1 4 8 _ { 0 } ^ { + 0 . 3 }";
        assertEquals(bs("varnothing ") + in, DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void mapsAlphaAlias() {
        String in = bs("alpha 1 3 _ { 0 } ^ { + 0 . 3 }");
        assertEquals(bs("varnothing 1 3 _ { 0 } ^ { + 0 . 3 }"),
                DiameterLatexNormalizer.normalize(in));
    }

    @Test
    void leavesUnrelatedLatex() {
        String in = bs("frac{1}{2}");
        assertEquals(in, DiameterLatexNormalizer.normalize(in));
        assertFalse(DiameterLatexNormalizer.normalize(in).contains("varnothing"));
    }
}
