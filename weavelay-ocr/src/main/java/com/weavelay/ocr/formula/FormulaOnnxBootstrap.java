package com.weavelay.ocr.formula;

import com.weavelay.core.formula.FormulaRecognitionService;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 PP-FormulaNet ONNX 挂到 {@link FormulaRecognitionService}。
 * <p>双引擎池：单引擎 {@code recognizePng} 仍 synchronized，跨引擎才能真正并行。
 */
public final class FormulaOnnxBootstrap {

    /**
     * 公式引擎池：2 路跨引擎并行。OCR 保持单引擎（再开一份 Det/Rec 会拖慢大表）。
     */
    public static final int POOL_SIZE = 2;

    private static volatile ArrayBlockingQueue<PpFormulaNetOnnxEngine> pool;
    private static volatile int poolSize;

    private FormulaOnnxBootstrap() {}

    /** 若模型存在则挂载本地识别；已挂过则复用同一引擎池。 */
    public static synchronized boolean attach(FormulaRecognitionService service) {
        if (service == null) {
            return false;
        }
        if (service.hasLocalRecognizer()) {
            return true;
        }
        if (!ensurePool()) {
            return false;
        }
        service.setLocalRecognizer(png -> {
            PpFormulaNetOnnxEngine engine = null;
            try {
                engine = borrow();
                if (engine == null) {
                    return "";
                }
                return engine.recognizePng(png);
            } catch (Exception e) {
                System.err.println("[PpFormulaNet] recognize failed: " + e.getMessage());
                return "";
            } finally {
                if (engine != null) {
                    release(engine);
                }
            }
        });
        return true;
    }

    /** 当前池大小（供调用方对齐并行度）。 */
    public static int poolSize() {
        return Math.max(1, poolSize);
    }

    private static boolean ensurePool() {
        if (pool != null && poolSize > 0) {
            return true;
        }
        PpFormulaNetOnnxEngine first = PpFormulaNetOnnxEngine.tryOpen();
        if (first == null) {
            return false;
        }
        ArrayBlockingQueue<PpFormulaNetOnnxEngine> q = new ArrayBlockingQueue<>(POOL_SIZE);
        q.offer(first);
        int n = 1;
        // 第二份失败则退回单引擎，仍可用
        if (POOL_SIZE > 1) {
            PpFormulaNetOnnxEngine second = PpFormulaNetOnnxEngine.tryOpen();
            if (second != null) {
                q.offer(second);
                n = 2;
            }
        }
        pool = q;
        poolSize = n;
        return true;
    }

    private static PpFormulaNetOnnxEngine borrow() throws InterruptedException {
        ArrayBlockingQueue<PpFormulaNetOnnxEngine> q = pool;
        if (q == null) {
            return null;
        }
        PpFormulaNetOnnxEngine eng = q.poll(60, TimeUnit.SECONDS);
        return eng;
    }

    private static void release(PpFormulaNetOnnxEngine engine) {
        ArrayBlockingQueue<PpFormulaNetOnnxEngine> q = pool;
        if (q == null || engine == null) {
            return;
        }
        if (!q.offer(engine)) {
            try {
                engine.close();
            } catch (Exception ignored) {
            }
        }
    }
}
