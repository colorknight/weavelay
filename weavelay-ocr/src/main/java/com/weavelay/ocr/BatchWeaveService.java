package com.weavelay.ocr;

import com.weavelay.core.extract.PageExtractResult;
import com.weavelay.core.extract.PageExtractService;
import com.weavelay.core.extract.RuleExtractor;
import com.weavelay.core.formula.FormulaRecognitionService;
import com.weavelay.core.formula.FormulaRegion;
import com.weavelay.ocr.formula.FormulaOnnxBootstrap;
import com.weavelay.ocr.layout.FormulaBoxSelector;
import com.weavelay.ocr.layout.PpDocLayoutBox;
import com.weavelay.ocr.layout.PpDocLayoutOnnxService;
import com.weavelay.ocr.layout.PpDocLayoutPageResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import com.weavelay.core.merge.RowMerger;
import com.weavelay.core.model.MergeParams;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveLayResult;
import com.weavelay.core.model.WeavePageResult;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.store.WeaveLayRuntime;
import com.weavelay.ocr.pdf.PdfPageRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF 按页渲染后 OCR, 再 merge; 保留每页 PNG 与检测框供预览.
 */
public final class BatchWeaveService {

    private final RapidOcrService ocrService;
    private PageExtractService pageExtractService;
    private FormulaRecognitionService formulaService;
    private PpDocLayoutOnnxService layoutService;

    public BatchWeaveService(RapidOcrService ocrService) {
        this.ocrService = ocrService;
        this.formulaService = new FormulaRecognitionService();
        FormulaOnnxBootstrap.attach(this.formulaService);
    }

    /** 获取内部公式识别服务. */
    public FormulaRecognitionService getFormulaService() {
        return formulaService;
    }

    /** 设置版式检测服务 (PP-DocLayout ONNX). 设置后 $ 字段会先检测公式区域再识别. */
    public void setLayoutService(PpDocLayoutOnnxService layoutService) {
        this.layoutService = layoutService;
    }

    public PpDocLayoutOnnxService getLayoutService() {
        return layoutService;
    }

    /** 是否启用了公式识别. */
    public boolean isFormulaRecognitionEnabled() {
        return formulaService != null;
    }

    public void resetPageExtractService() {
        this.pageExtractService = null;
    }

    /** 仅渲染 PDF 页为 PNG, 不执行 OCR. */
    public List<WeavePageResult> renderPdfPagesOnly(Path pdfPath) throws Exception {
        if (pdfPath == null || !Files.isRegularFile(pdfPath)) {
            return Collections.emptyList();
        }
        String fileName = pdfPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("不是 PDF 文件: " + fileName);
        }
        List<byte[]> pageImages = PdfPageRenderer.renderToPngBytes(pdfPath);
        List<WeavePageResult> pages = new ArrayList<WeavePageResult>(pageImages.size());
        int total = pageImages.size();
        for (int i = 0; i < total; i++) {
            int pageNum = i + 1;
            String streamName = pageStreamName(fileName, pageNum);
            byte[] png = pageImages.get(i);
            List<OcrRow> emptyRows = Collections.emptyList();
            pages.add(new WeavePageResult(streamName, pageNum, png, emptyRows));
        }
        return pages;
    }

    public WeaveLayResult weavePdfSession(Path pdfPath, MergeParams params) throws Exception {
        return weavePdfSession(pdfPath, params, null, null);
    }

    public WeaveLayResult weavePdfSession(
            Path pdfPath,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener) throws Exception {
        return weavePdfSession(pdfPath, params, progress, pageListener, null);
    }

    public WeaveLayResult weavePdfSession(
            Path pdfPath,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener,
            WeaveCancelSignal cancel) throws Exception {
        if (pdfPath == null || !Files.isRegularFile(pdfPath)) {
            return emptyResult();
        }
        String fileName = pdfPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("不是 PDF 文件: " + fileName);
        }

        checkCancelled(cancel);
        if (progress != null) {
            progress.onProgress(0, 0, "渲染 PDF 页面...");
        }
        List<byte[]> pageImages = PdfPageRenderer.renderToPngBytes(pdfPath);
        checkCancelled(cancel);
        return weaveRenderedPages(fileName, pageImages, params, progress, pageListener, cancel);
    }

    public WeaveLayResult weavePdfFolderSession(
            Path folder,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener) throws Exception {
        return weavePdfFolderSession(folder, params, progress, pageListener, null);
    }

    public WeaveLayResult weavePdfFolderSession(
            Path folder,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener,
            WeaveCancelSignal cancel) throws Exception {
        if (folder == null || !Files.isDirectory(folder)) {
            return emptyResult();
        }
        List<Path> pdfs = listPdfFiles(folder);
        if (pdfs.isEmpty()) {
            return emptyResult();
        }

        List<WeavePageResult> pages = new ArrayList<WeavePageResult>();
        int totalFiles = pdfs.size();
        int globalPageIndex = 0;

        for (int fileIndex = 0; fileIndex < totalFiles; fileIndex++) {
            checkCancelled(cancel);
            Path pdfPath = pdfs.get(fileIndex);
            String fileName = pdfPath.getFileName().toString();
            int fileNum = fileIndex + 1;
            if (progress != null) {
                progress.onProgress(fileNum, totalFiles,
                        "渲染 " + fileName + " (" + fileNum + "/" + totalFiles + ")");
            }
            List<byte[]> pageImages = PdfPageRenderer.renderToPngBytes(pdfPath);
            checkCancelled(cancel);
            for (int pageIndex = 0; pageIndex < pageImages.size(); pageIndex++) {
                checkCancelled(cancel);
                globalPageIndex++;
                int pageNum = pageIndex + 1;
                String streamName = pageStreamName(fileName, pageNum);
                byte[] png = pageImages.get(pageIndex);
                if (progress != null) {
                    progress.onProgress(globalPageIndex, 0,
                            fileName + " OCR 第 " + pageNum + "/" + pageImages.size() + " 页"
                                    + " (文件 " + fileNum + "/" + totalFiles + ")");
                }
                List<OcrRow> ocrRows = ocrService.recognizeImageBytes(png, streamName);
                checkCancelled(cancel);
                WeavePageResult page = buildPageResult(
                        streamName, pageNum, pageImages.size(), png, ocrRows);
                pages.add(page);
                if (pageListener != null) {
                    pageListener.onPageCompleted(page, globalPageIndex, 0);
                }
            }
        }
        checkCancelled(cancel);
        if (progress != null) {
            progress.onProgress(pages.size(), pages.size(), "合并行...");
        }
        return new WeaveLayResult(pages, collectWeaveRows(pages));
    }

    private WeaveLayResult weaveRenderedPages(
            String fileName,
            List<byte[]> pageImages,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener) throws Exception {
        return weaveRenderedPages(fileName, pageImages, params, progress, pageListener, null);
    }

    private WeaveLayResult weaveRenderedPages(
            String fileName,
            List<byte[]> pageImages,
            MergeParams params,
            WeaveProgressListener progress,
            WeavePageListener pageListener,
            WeaveCancelSignal cancel) throws Exception {
        int totalPages = pageImages.size();
        if (totalPages == 0) {
            return emptyResult();
        }

        List<WeavePageResult> pages = new ArrayList<WeavePageResult>(totalPages);
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            checkCancelled(cancel);
            int pageNum = pageIndex + 1;
            String streamName = pageStreamName(fileName, pageNum);
            byte[] png = pageImages.get(pageIndex);

            if (progress != null) {
                progress.onProgress(pageNum, totalPages,
                        "OCR 第 " + pageNum + "/" + totalPages + " 页");
            }
            List<OcrRow> ocrRows = ocrService.recognizeImageBytes(png, streamName);
            checkCancelled(cancel);

            WeavePageResult page = buildPageResult(
                    streamName, pageNum, pageImages.size(), png, ocrRows);
            pages.add(page);
            if (pageListener != null) {
                pageListener.onPageCompleted(page, pageNum, totalPages);
            }
        }
        checkCancelled(cancel);
        if (progress != null) {
            progress.onProgress(totalPages, totalPages, "合并行...");
        }
        return new WeaveLayResult(pages, collectWeaveRows(pages));
    }

    private static void checkCancelled(WeaveCancelSignal cancel) {
        if (cancel != null) {
            cancel.check();
        }
    }

    private WeavePageResult buildPageResult(
            String streamName,
            int pageNum,
            int totalPages,
            byte[] png,
            List<OcrRow> ocrRows) throws Exception {
        PageExtractService extractService = resolveExtractService();
        PageExtractResult extracted = extractService.processPage(
                streamName, ocrRows, pageNum, totalPages);

        List<SlotValue> slotValues = new ArrayList<>(extracted.getSlotValues());

        PageKindDefinition definition = extractService.getCatalog()
                .getDefinitionByCode(extracted.getPageKindCode());

        // 公式二次识别: 先对原始提取结果做 (此时 valueRow 还有坐标),
        // 暂存 LaTeX 结果, merge 后再写入 (避免被 RuleExtractor 的无坐标结果覆盖)
        Map<String, String> formulaLatexMap = Collections.emptyMap();
        if (formulaService != null && definition != null && png != null && png.length > 0) {
            try {
                formulaLatexMap = recognizeFormulaRegions(png, definition, slotValues);
            } catch (Exception e) {
                System.err.println("[BatchWeave] 公式识别失败, 使用原始 OCR 文本:");
                e.printStackTrace();
            }
        }

        if (definition != null) {
            List<SlotValue> regexSlots = RuleExtractor.extract(
                    definition.getSlots(), ocrRows);
            slotValues = mergeLayoutSlots(slotValues, regexSlots);
        }

        // 将暂存的公式 LaTeX 结果写入 merge 后的 slotValues
        if (!formulaLatexMap.isEmpty()) {
            slotValues = applyFormulaLatex(slotValues, formulaLatexMap);
        }

        return new WeavePageResult(
                streamName,
                pageNum,
                png,
                ocrRows,
                extractService.getCatalog().getDocumentFamily(),
                extractService.getDocumentFamilyCode(),
                extractService.getDocumentFamilyDisplayName(),
                extracted.getPageKind(),
                extracted.getPageKindCode(),
                extractService.pageKindDisplayName(extracted.getPageKindCode()),
                false,
                slotValues,
                extracted.getWeaveRows());
    }

    private static List<SlotValue> mergeLayoutSlots(
            List<SlotValue> existing, List<SlotValue> layoutSlots) {
        if (layoutSlots == null || layoutSlots.isEmpty()) {
            return existing;
        }
        List<SlotValue> merged = new ArrayList<>();
        java.util.Set<String> layoutCodes = new java.util.LinkedHashSet<>();
        for (SlotValue ls : layoutSlots) {
            layoutCodes.add(ls.getSlotCode());
        }
        for (SlotValue ex : existing) {
            if (!layoutCodes.contains(ex.getSlotCode())) {
                merged.add(ex);
            }
        }
        merged.addAll(layoutSlots);
        return merged;
    }

    /**
     * 从原始提取结果中收集公式区域, 调用识别 API, 打印 OCR vs LaTeX 对比,
     * 返回 slotCode → LaTeX 的映射.
     * 必须在 mergeLayoutSlots 之前调用, 否则 valueRow 可能已被覆盖为 null.
     */
    private Map<String, String> recognizeFormulaRegions(
            byte[] pagePng,
            PageKindDefinition definition,
            List<SlotValue> originalSlots) {

        // 1. 收集需要公式识别的区域, 并记录原始 OCR 文本
        List<FormulaRegion> regions = new ArrayList<>();
        java.util.LinkedHashMap<String, String> ocrOriginMap = new java.util.LinkedHashMap<>();

        // 1a. 普通公式字段: SlotDefinition.isFormulaField()
        for (SlotDefinition slotDef : definition.getSlots()) {
            if (!slotDef.isFormulaField()) {
                continue;
            }
            SlotValue sv = findSlotValue(originalSlots, slotDef.getSlotCode());
            if (sv == null) {
                continue;
            }
            OcrRow vr = sv.getValueRow();
            if (vr == null || !vr.hasBbox()) {
                continue;
            }
            int x = (int) vr.getStartX();
            int y = (int) vr.getStartY();
            int w = (int) (vr.getEndX() - vr.getStartX());
            int h = (int) (vr.getEndY() - vr.getStartY());
            if (w <= 0 || h <= 0) {
                continue;
            }
            regions.add(new FormulaRegion(slotDef.getSlotCode(), x, y, w, h));
            ocrOriginMap.put(slotDef.getSlotCode(), sv.getValue());
        }

        // 1b. 表格公式列: SlotDefinition.isFormulaColumn(columnName)
        for (SlotDefinition slotDef : definition.getSlots()) {
            if (!slotDef.isTable()) {
                continue;
            }
            for (SlotValue sv : originalSlots) {
                if (!slotDef.isFormulaColumn(sv.getSlotLabel())) {
                    continue;
                }
                OcrRow vr = sv.getValueRow();
                if (vr == null || !vr.hasBbox()) {
                    continue;
                }
                int x = (int) vr.getStartX();
                int y = (int) vr.getStartY();
                int w = (int) (vr.getEndX() - vr.getStartX());
                int h = (int) (vr.getEndY() - vr.getStartY());
                if (w <= 0 || h <= 0) {
                    continue;
                }
                regions.add(new FormulaRegion(sv.getSlotCode(), x, y, w, h));
                ocrOriginMap.put(sv.getSlotCode(), sv.getValue());
            }
        }

        if (regions.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 对每个区域做版式检测，区分公式和文字
        Map<String, String> latexMap = new java.util.LinkedHashMap<>();

        for (FormulaRegion region : regions) {
            String code = region.getSlotCode();
            // 2a. 裁剪区域
            byte[] cropPng = cropRegionPng(pagePng, region);
            if (cropPng == null) {
                continue;
            }

            // 2b. 版式检测（低阈值 + NMS，捞单元格内多段公式）
            List<PpDocLayoutBox> allBoxes = detectAllBoxes(cropPng);
            int cellW = 0;
            int cellH = 0;
            try {
                var cellImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cropPng));
                if (cellImg != null) {
                    cellW = cellImg.getWidth();
                    cellH = cellImg.getHeight();
                }
            } catch (Exception ignored) {
            }
            List<PpDocLayoutBox> formulaBoxes =
                    com.weavelay.ocr.layout.FormulaBoxSelector.select(allBoxes, cellW, cellH);

            if (!formulaBoxes.isEmpty()) {
                List<FormulaMixedComposer.FormulaPiece> pieces = new ArrayList<>();
                for (PpDocLayoutBox box : formulaBoxes) {
                    int x = Math.max(0, (int) box.getXmin());
                    int y = Math.max(0, (int) box.getYmin());
                    int w = Math.max(1, (int) box.getWidth());
                    int h = Math.max(1, (int) box.getHeight());
                    try {
                        var cellImg = ImageIO.read(new ByteArrayInputStream(cropPng));
                        if (cellImg != null) {
                            var ink = FormulaInkBounds.expandForDiameter(cellImg, x, y, w, h);
                            x = ink.x();
                            y = ink.y();
                            w = ink.w();
                            h = ink.h();
                        }
                    } catch (Exception ignored) {
                    }
                    byte[] subCrop = cropRegionPng(cropPng, new FormulaRegion(code, x, y, w, h));
                    if (subCrop == null) continue;
                    String subLatex = formulaService.recognizeSingle(subCrop);
                    subLatex = FormulaDigitSalvage.salvageIfNoDigit(subLatex, subCrop, ocrService);
                    if (subLatex == null || subLatex.isEmpty()) continue;
                    pieces.add(new FormulaMixedComposer.FormulaPiece(
                            x, y, w, h, subLatex,
                            "display_formula".equals(box.getLabelName())));
                }
                if (!pieces.isEmpty()) {
                    String mixed = FormulaMixedComposer.compose(
                            cropPng, pieces, ocrService);
                    if (mixed != null && !mixed.isEmpty()) {
                        latexMap.put(code, mixed);
                    }
                }
            }
        }

        return latexMap;
    }

    /** 从页面 PNG 裁剪指定区域. */
    private static byte[] cropRegionPng(byte[] pagePng, FormulaRegion region) {
        try {
            BufferedImage page = ImageIO.read(new ByteArrayInputStream(pagePng));
            if (page == null) return null;
            int x = Math.max(0, region.getX());
            int y = Math.max(0, region.getY());
            int w = Math.min(region.getW(), page.getWidth() - x);
            int h = Math.min(region.getH(), page.getHeight() - y);
            if (w <= 0 || h <= 0) return null;
            BufferedImage sub = page.getSubimage(x, y, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(sub, "PNG", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 对裁剪图片做版式检测, 返回全部布局块 (按阅读顺序). */
    private List<PpDocLayoutBox> detectAllBoxes(byte[] cropPng) {
        if (layoutService == null) {
            return Collections.emptyList();
        }
        float thr = Math.min(
                layoutService.getDefaultScoreThreshold(),
                FormulaBoxSelector.CELL_FORMULA_SCORE_MIN);
        List<PpDocLayoutBox> all = new ArrayList<>();
        // enhance 在 PpDocLayoutOnnxService 中未真正改变张量，单次检测即可
        try {
            PpDocLayoutPageResult layout = layoutService.detect(cropPng, thr, false);
            if (layout != null && layout.getBoxes() != null) {
                all.addAll(layout.getBoxes());
            }
        } catch (Exception ignored) {
        }
        return all;
    }

    /**
     * 将公式识别的 LaTeX 结果写入 merge 后的 SlotValue 列表.
     */
    private static List<SlotValue> applyFormulaLatex(
            List<SlotValue> mergedSlots,
            Map<String, String> latexMap) {
        List<SlotValue> updated = new ArrayList<>();
        for (SlotValue sv : mergedSlots) {
            String latex = latexMap.get(sv.getSlotCode());
            if (latex != null && !latex.isEmpty()) {
                updated.add(new SlotValue(
                        sv.getSlotCode(),
                        sv.getSlotLabel(),
                        latex,
                        sv.getLabelRow(),
                        sv.getValueRow()));
            } else {
                updated.add(sv);
            }
        }
        return updated;
    }

    private static SlotValue findSlotValue(List<SlotValue> slots, String slotCode) {
        for (SlotValue sv : slots) {
            if (sv.getSlotCode().equals(slotCode)) {
                return sv;
            }
        }
        return null;
    }

    private PageExtractService resolveExtractService() throws Exception {
        if (pageExtractService == null) {
            pageExtractService = WeaveLayRuntime.open().getPageExtractService();
        }
        return pageExtractService;
    }

    private static List<WeaveRow> collectWeaveRows(List<WeavePageResult> pages) {
        List<WeaveRow> all = new ArrayList<WeaveRow>();
        for (WeavePageResult page : pages) {
            all.addAll(page.getPageWeaveRows());
        }
        return all;
    }

    private static WeaveLayResult emptyResult() {
        return new WeaveLayResult(Collections.<WeavePageResult>emptyList(),
                Collections.<WeaveRow>emptyList());
    }

    static String pageStreamName(String pdfFileName, int pageNum) {
        return pdfFileName + "#p" + String.format(Locale.ROOT, "%04d", pageNum);
    }

    private static List<Path> listPdfFiles(Path folder) throws IOException {
        List<Path> pdfs = new ArrayList<Path>();
        for (Path path : Files.newDirectoryStream(folder)) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".pdf")) {
                pdfs.add(path);
            }
        }
        Collections.sort(pdfs, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        return pdfs;
    }

}
