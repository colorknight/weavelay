package com.weavelay.ocr;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/**
 * 公式 / 版面会话用的 CPU 选项。不要在这里改全局线程池，
 * 否则 RapidOCR 的 Det/Rec 会被一起锁死，容易漏字。
 */
public final class OrtCpuSessions {

    static final int FORMULA_INTRA_OP_THREADS = 1;
    static final int FORMULA_INTER_OP_THREADS = 1;

    private OrtCpuSessions() {}

    public static OrtEnvironment environment() throws OrtException {
        return OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
    }

    public static OrtSession.SessionOptions newSessionOptions() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(FORMULA_INTRA_OP_THREADS);
        opts.setInterOpNumThreads(FORMULA_INTER_OP_THREADS);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
        return opts;
    }
}
