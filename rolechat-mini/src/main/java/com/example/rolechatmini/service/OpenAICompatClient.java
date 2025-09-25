package com.example.rolechatmini.service;

import com.example.rolechatmini.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAICompatClient {
    private final AppProperties props;

    public void streamChat(String system, String user, SseEmitter emitter) throws Exception {
        String base = props.getLlm().getBaseUrl();
        // 规范化base，确保包含 /v1 前缀
        if (!base.matches(".*/v\\d+/?$")) {
            base = base.endsWith("/") ? base + "v1" : base + "/v1";
        }
        String url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
        String body = "{\n" +
                "  \"model\": \"" + props.getLlm().getModel() + "\",\n" +
                "  \"stream\": true,\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"system\",\"content\": " + jsonStr(system) + "},\n" +
                "    {\"role\":\"user\",\"content\": " + jsonStr(user) + "}\n" +
                "  ]\n" +
                "}";
        HttpPost post = new HttpPost(url);
        post.setHeader("Authorization", "Bearer " + props.getLlm().getApiKey());
        post.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        post.setHeader("Accept", "text/event-stream");
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            client.execute(post, response -> {
                int code = response.getCode();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                    if (code < 200 || code >= 300) {
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) { sb.append(l).append('\n'); }
                        String err = sb.toString();
                        log.error("LLM stream error: status={}, body={}", code, err);
                        // 以 OpenAI 流式 delta 结构返回，前端能正常展示
                        String msg = sanitize(err.isBlank() ? ("HTTP " + code) : err);
                        String deltaLike = "{\"choices\":[{\"delta\":{\"content\":\"[错误] " + msg + "\"}}]}";
                        try { emitter.send(SseEmitter.event().data(deltaLike)); } catch (Exception ignore) {}
                        emitter.complete();
                        return null;
                    }
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) break;
                        emitter.send(SseEmitter.event().data(payload));
                    }
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
                return null;
            });
        }
    }

    public String asrTranscribeBase64(String audioBase64, String format) {
        try {
            // 解码base64音频数据
            byte[] audioData = Base64.getDecoder().decode(audioBase64);
            
            // 构建ASR请求URL
            String url = props.getAsr().getBaseUrl();
            
            // 创建HTTP客户端和请求
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(url);
                post.setHeader("Authorization", "Bearer " + props.getAsr().getApiKey());
                post.setHeader("Content-Type", "application/octet-stream");
                
                // 添加七牛云特定的请求参数
                String params = "model=" + props.getAsr().getModel() + "&format=" + format;
                post.setUri(new java.net.URI(url + "?" + params));
                
                // 发送音频数据
                post.setEntity(new ByteArrayEntity(audioData, ContentType.DEFAULT_BINARY));
                
                // 执行请求并处理响应
                return client.execute(post, response -> {
                    int code = response.getCode();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        
                        if (code >= 200 && code < 300) {
                            // 解析成功响应中的文本
                            String responseJson = sb.toString();
                            // 简单解析JSON响应，实际项目中应使用JSON库
                            String text = parseAsrResult(responseJson);
                            return text != null ? text : "[ASR错误] 无法解析响应";
                        } else {
                            log.error("ASR error: status={}, body={}", code, sb.toString());
                            return "[ASR错误] HTTP " + code;
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("ASR processing error", e);
            return "[ASR错误] " + e.getMessage();
        }
    }

    public String ttsSynthesizeBase64(String text, String voice) {
        try {
            // 构建TTS请求URL
            String url = props.getTts().getBaseUrl();
            
            // 创建HTTP客户端和请求
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(url);
                post.setHeader("Authorization", "Bearer " + props.getTts().getApiKey());
                post.setHeader("Content-Type", "application/json");
                
                // 使用默认语音类型，如果未指定或为"auto"
                String voiceType = (voice != null && !voice.equals("auto")) ? voice : "qiniu_zh_female_tmjxxy";
                
                // 构造七牛云TTS请求体 - 根据API文档调整结构
                String requestBody = "{\n" +
                    "  \"request\": {\n" +
                    "    \"text\": \"" + escapeJsonString(text) + "\"\n" +
                    "  },\n" +
                    "  \"audio\": {\n" +
                    "    \"voice_type\": \"" + voiceType + "\",\n" +
                    "    \"encoding\": \"mp3\"\n" +
                    "  }\n" +
                    "}";
                
                post.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
                
                // 执行请求并处理响应
                return client.execute(post, response -> {
                    int code = response.getCode();
                    log.info("TTS response code: {}", code);
                    
                    if (code >= 200 && code < 300) {
                        // 读取响应数据
                        try (InputStream is = response.getEntity().getContent()) {
                            String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            log.debug("TTS raw response: {}", responseStr);
                            
                            // 解析JSON响应以提取音频数据
                            try {
                                // 简单的JSON解析来提取data字段
                                int dataIndex = responseStr.indexOf("\"data\":\"");
                                if (dataIndex != -1) {
                                    int start = dataIndex + 8; // "\"data\":\"".length()
                                    int end = responseStr.indexOf("\"", start);
                                    if (end != -1) {
                                        String base64Audio = responseStr.substring(start, end);
                                        log.info("Extracted base64 audio length: {}", base64Audio.length());
                                        
                                        // 验证提取的数据是否是有效的base64音频
                                        try {
                                            byte[] decoded = Base64.getDecoder().decode(base64Audio);
                                            log.info("Decoded audio data size: {} bytes", decoded.length);
                                            if (decoded.length > 0) {
                                                return base64Audio;
                                            }
                                        } catch (Exception decodeEx) {
                                            log.error("Failed to decode extracted base64 audio", decodeEx);
                                        }
                                    }
                                }
                                
                                log.warn("Failed to extract audio data from response, returning error message");
                                String errorMessage = "[TTS错误] 无法从响应中提取音频数据";
                                return Base64.getEncoder().encodeToString(errorMessage.getBytes(StandardCharsets.UTF_8));
                            } catch (Exception parseEx) {
                                log.error("Error parsing TTS response", parseEx);
                                String errorMessage = "[TTS错误] 解析响应失败: " + parseEx.getMessage();
                                return Base64.getEncoder().encodeToString(errorMessage.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    } else {
                        // 错误处理
                        try (InputStream is = response.getEntity().getContent()) {
                            String errorBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            log.error("TTS error: status={}, body={}", code, errorBody);
                            
                            // 返回错误信息的base64编码
                            String errorMessage = "[TTS错误] HTTP " + code + " - " + errorBody;
                            return Base64.getEncoder().encodeToString(errorMessage.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            log.error("Failed to read TTS error response", e);
                            return Base64.getEncoder().encodeToString(("[TTS错误] HTTP " + code).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("TTS processing error", e);
            // 返回错误信息的base64编码
            return Base64.getEncoder().encodeToString(("[TTS错误] " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String jsonStr(String s) {
        if (s == null) {
            s = "";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    
    // 添加用于转义JSON字符串的方法
    private String escapeJsonString(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f");
    }
    
    // 简单的JSON结果解析方法（实际项目中建议使用专业JSON库）
    private String parseAsrResult(String jsonResponse) {
        // 解析七牛云ASR响应
        // 例如: {"code":0,"message":"OK","result":"你好世界"}
        int textIndex = jsonResponse.indexOf("\"result\":\"");
        if (textIndex != -1) {
            int start = textIndex + 10; // "\"result\":\"".length()
            int end = jsonResponse.indexOf("\"", start);
            if (end != -1) {
                return jsonResponse.substring(start, end)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
            }
        }
        return null;
    }
}