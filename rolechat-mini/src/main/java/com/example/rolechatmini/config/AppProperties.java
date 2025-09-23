package com.example.rolechatmini.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Llm llm = new Llm();
    private Asr asr = new Asr();
    private Tts tts = new Tts();

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @Data
    public static class Asr {
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @Data
    public static class Tts {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String voice;
    }
}
