package com.weavelay.ocr;

/**
 * 历史 JNI / exe 引导入口。RapidOCR4j 进程内加载 ORT+OpenCV，此处保留 no-op 以兼容调用方。
 */
final class NativeRuntimeBootstrap {

    private NativeRuntimeBootstrap() {
    }

    static void prepareClassLoader() {
        TempDirBootstrap.ensure();
    }
}
