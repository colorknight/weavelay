package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;

import java.nio.file.Path;
import java.util.List;

/**
 * OCR 服务抽象: 本地 RapidOCR ONNX 引擎.
 */
public interface OcrService {

    /**
     * 对图片字节执行 OCR, 返回带坐标的文字行列表.
     */
    List<OcrRow> recognizeImageBytes(byte[] imageBytes, String streamName) throws Exception;

    /**
     * 对图片文件执行 OCR, 返回带坐标的文字行列表.
     */
    List<OcrRow> recognizeImage(Path imagePath, String streamName) throws Exception;

    /**
     * 后台预热: 加载模型或检查远程服务可达.
     */
    void warmupInBackground(java.util.function.Consumer<String> statusCallback);

    /**
     * 取消正在进行的识别任务.
     */
    void cancelInFlightRecognition();
}
