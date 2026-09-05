package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PpOcrV5ResultParserTest {

    @TempDir
    Path tempDir;

    @Test
    void subtractsPaddingFromLoggedTextBoxCoords() throws Exception {
        Path resultTxt = tempDir.resolve("page.png-result.txt");
        Files.writeString(resultTxt, """
                TextBox[0](+padding)[score(0.95),[x: 150, y: 450], [x: 900, y: 450], [x: 900, y: 520], [x: 150, y: 520]]
                textLine[0](工艺规程)
                """, StandardCharsets.UTF_8);

        List<OcrRow> rows = PpOcrV5ResultParser.parse(resultTxt, "p1", 0f, 50);

        assertEquals(1, rows.size());
        OcrRow row = rows.get(0);
        assertEquals(100, row.getStartX(), 0.01);
        assertEquals(400, row.getStartY(), 0.01);
        assertEquals(850, row.getEndX(), 0.01);
        assertEquals(470, row.getEndY(), 0.01);
    }

    @Test
    void keepsCoordsWhenPaddingZero() throws Exception {
        Path resultTxt = tempDir.resolve("page.png-result.txt");
        Files.writeString(resultTxt, """
                TextBox[0](+padding)[score(0.95),[x: 100, y: 400], [x: 900, y: 400], [x: 900, y: 520], [x: 100, y: 520]]
                textLine[0](工艺规程)
                """, StandardCharsets.UTF_8);

        List<OcrRow> rows = PpOcrV5ResultParser.parse(resultTxt, "p1", 0f, 0);

        assertEquals(100, rows.get(0).getStartX(), 0.01);
        assertEquals(400, rows.get(0).getStartY(), 0.01);
    }
}
