package com.weavelay.service.web;

import com.weavelay.core.model.MergeParams;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeaveLayResult;
import com.weavelay.core.model.WeavePageResult;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.ocr.BatchWeaveService;
import com.weavelay.ocr.RapidOcrService;
import com.weavelay.service.web.dto.OcrBoxDto;
import com.weavelay.service.web.dto.WeavePageDto;
import com.weavelay.service.web.dto.WeaveRowDto;
import com.weavelay.service.web.dto.WeaveSessionDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/weave")
public class WeaveController {

    private final BatchWeaveService batchWeaveService;

    public WeaveController(RapidOcrService rapidOcrService) {
        this.batchWeaveService = new BatchWeaveService(rapidOcrService);
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WeaveSessionDto weavePdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "yThreshold", defaultValue = "80") double yThreshold,
            @RequestParam(value = "xThreshold", defaultValue = "350") double xThreshold) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传 PDF 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 PDF");
        }

        Path tmp = Files.createTempFile("weavelay-upload-", ".pdf");
        try {
            file.transferTo(tmp);
            MergeParams params = new MergeParams();
            params.setYThreshold(yThreshold);
            params.setXThreshold(xThreshold);
            WeaveLayResult result = batchWeaveService.weavePdfSession(tmp, params);
            return toDto(result);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
    }

    private static WeaveSessionDto toDto(WeaveLayResult result) throws IOException {
        WeaveSessionDto dto = new WeaveSessionDto();
        List<WeavePageDto> pages = new ArrayList<WeavePageDto>();
        for (WeavePageResult page : result.getPages()) {
            pages.add(toPageDto(page));
        }
        dto.setPages(pages);

        List<WeaveRowDto> rows = new ArrayList<WeaveRowDto>();
        for (WeaveRow row : result.getWeaveRows()) {
            rows.add(new WeaveRowDto(row.getStreamName(), row.getFeature(), row.getSeq()));
        }
        dto.setWeaveRows(rows);
        return dto;
    }

    private static WeavePageDto toPageDto(WeavePageResult page) throws IOException {
        byte[] png = page.getPagePng();
        int width = 0;
        int height = 0;
        if (png.length > 0) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        }
        WeavePageDto dto = new WeavePageDto();
        dto.setStreamName(page.getStreamName());
        dto.setPageNumber(page.getPageNumber());
        dto.setImageBase64(Base64.getEncoder().encodeToString(png));
        dto.setWidth(width);
        dto.setHeight(height);
        dto.setOcrRows(toBoxes(page.getOcrRows()));
        return dto;
    }

    private static List<OcrBoxDto> toBoxes(List<OcrRow> rows) {
        List<OcrBoxDto> boxes = new ArrayList<OcrBoxDto>();
        for (OcrRow row : rows) {
            boxes.add(new OcrBoxDto(
                    row.getFeature(),
                    row.getProb(),
                    row.getStartX(),
                    row.getStartY(),
                    row.getEndX(),
                    row.getEndY()));
        }
        return boxes;
    }
}
