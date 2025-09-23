package com.example.rolechatmini.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

public class ChatDtos {

    @Data
    public static class ChatRequest {
        @NotBlank
        private String roleId; // 角色标识
        private String userText; // 文本输入
        private String audioBase64; // 语音输入（可选）
        private List<Message> history; // 简易历史
        private String answerContentType; // text | audio
        private String preferredVoice; // 可覆盖默认voice
    }

    @Data
    public static class Message {
        private String role; // user|assistant|system
        private String content;
    }

    @Data
    public static class AsrUploadRequest {
        @NotBlank
        private String audioBase64;
        private String format; // wav/mp3/webm
    }

    @Data
    public static class TtsRequest {
        @NotBlank
        private String text;
        private String voice;
    }
}
