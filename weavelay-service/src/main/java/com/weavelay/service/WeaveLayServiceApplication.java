package com.weavelay.service;

import com.weavelay.ocr.RapidOcrService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WeaveLayServiceApplication {

    public static void main(String[] args) {
        // 先拨临时目录到工程盘，再加载 ORT 原生库
        RapidOcrService.prepareNativeRuntime();
        SpringApplication.run(WeaveLayServiceApplication.class, args);
    }

    @Bean
    RapidOcrService rapidOcrService() {
        return new RapidOcrService();
    }
}
