package com.example.rolechatmini.service;

import com.example.rolechatmini.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAICompatClient {
    private final AppProperties props;

    @PostConstruct
    public void init() {
        log.info("OpenAICompatClient initialized with ASR config - AppId: {}, ApiKey: {}, ApiSecret: {}",
                props.getAsr().getAppId(),
                props.getAsr().getApiKey() != null ? "****" + props.getAsr().getApiKey().substring(Math.max(0, props.getAsr().getApiKey().length() - 4)) : "null",
                props.getAsr().getApiSecret() != null ? "****" + props.getAsr().getApiSecret().substring(Math.max(0, props.getAsr().getApiSecret().length() - 4)) : "null");
    }

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

    // 讯飞ASR实现（使用REST API方式）
    public String asrTranscribeBase64(String audioBase64, String format) {
        try {
            log.info("XF ASR input audio data length: {} bytes, format: {}", audioBase64.length(), format);
            
            byte[] audioData = Base64.getDecoder().decode(audioBase64);
            log.info("XF Decoded audio data length: {} bytes", audioData.length);
            
            if ("pcm".equalsIgnoreCase(format)) {
                // 前端已提供 PCM，直接使用
                log.info("Using direct PCM data for XF ASR");
                return transcribeWithXfWebSocket(audioData);
            } else {
                // 其他格式（如 webm/wav）仍走转换流程
                log.info("Converting {} audio to PCM format", format);
                audioData = convertToPcm(audioData, format);
                log.info("Converted PCM audio data length: {} bytes", audioData.length);
                return transcribeWithXfWebSocket(audioData);
            }
        } catch (Exception e) {
            log.error("XF ASR processing error", e);
            return "[ASR错误] " + e.getMessage();
        }
    }

    // 使用讯飞WebSocket API实现ASR
    private String transcribeWithXfWebSocket(byte[] audioData) throws Exception {
        String hostUrl = "https://iat-api.xfyun.cn/v2/iat";
        String appid = props.getAsr().getAppId();
        String apiSecret = props.getAsr().getApiSecret();
        String apiKey = props.getAsr().getApiKey();
        
        log.info("Using XF WebSocket ASR config - AppId: {}, ApiKey: {}", appid, apiKey != null ? "****" + apiKey.substring(Math.max(0, apiKey.length() - 4)) : "null");
        
        // 检查配置是否正确加载
        if (appid == null || appid.isEmpty()) {
            log.error("XF ASR AppId not properly configured: {}", appid);
            return "[ASR错误] 讯飞AppId未正确配置";
        }
        
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("XF ASR ApiKey not properly configured");
            return "[ASR错误] 讯飞ApiKey未正确配置";
        }
        
        if (apiSecret == null || apiSecret.isEmpty()) {
            log.error("XF ASR ApiSecret not properly configured");
            return "[ASR错误] 讯飞ApiSecret未正确配置";
        }
        
        // 构建鉴权url
        String authUrl = getAuthUrl(hostUrl, apiKey, apiSecret);
        String wsUrl = authUrl.replace("http://", "ws://").replace("https://", "wss://");
        log.info("XF WebSocket ASR URL: {}", wsUrl);
        
        // 用于存储识别结果的引用
        StringBuilder fullResult = new StringBuilder();
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        
        // 创建 WebSocket.Listener
        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            @Override
            public void onOpen(java.net.http.WebSocket webSocket) {
                log.info("XF WebSocket connection opened");
                // 启动发送音频数据的线程
                sendAudioData(webSocket, appid, audioData);
                java.net.http.WebSocket.Listener.super.onOpen(webSocket);
            }
            
            @Override
            public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                log.debug("XF Received text message: {}", data);
                try {
                    // 解析讯飞返回的JSON结果
                    ObjectMapper mapper = new ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(data.toString());
                    
                    int code = root.has("code") ? root.get("code").asInt() : -1;
                    if (code != 0) {
                        String message = root.has("message") ? root.get("message").asText() : "Unknown error";
                        log.error("XF ASR error: code={}, message={}", code, message);
                        resultFuture.completeExceptionally(new Exception("XF ASR error: " + message));
                        return java.net.http.WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                    
                    // 记录sid（会话ID）
                    if (root.has("sid")) {
                        String sid = root.get("sid").asText();
                        log.info("XF ASR session ID: {}", sid);
                    }
                    
                    if (root.has("data")) {
                        com.fasterxml.jackson.databind.JsonNode dataNode = root.get("data");
                        log.debug("XF ASR data node: {}", dataNode.toPrettyString());
                        
                        if (dataNode.has("result")) {
                            com.fasterxml.jackson.databind.JsonNode resultNode = dataNode.get("result");
                            // 解析识别结果
                            String resultText = parseXfResult(resultNode);
                            if (resultText != null && !resultText.isEmpty()) {
                                // 只有当有新文本时才追加
                                if (!resultText.isEmpty()) {
                                    if (fullResult.length() > 0) {
                                        fullResult.append(" ");
                                    }
                                    fullResult.append(resultText);
                                    log.info("XF ASR partial result: {}", resultText);
                                }
                            } else {
                                log.debug("XF ASR result text is empty or null");
                            }
                        } else {
                            log.debug("XF ASR data node does not contain result");
                        }
                        
                        // 检查是否是最后一帧
                        int status = dataNode.has("status") ? dataNode.get("status").asInt() : -1;
                        log.debug("XF ASR data status: {}", status);
                        if (status == 2) {
                            log.info("XF ASR completed with final result: '{}'", fullResult.toString());
                            webSocket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Finished");
                        }
                    } else {
                        log.debug("XF ASR response does not contain data node");
                    }
                } catch (Exception e) {
                    log.error("Error parsing XF WebSocket response: {}", data, e);
                }
                return java.net.http.WebSocket.Listener.super.onText(webSocket, data, last);
            }
            
            @Override
            public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                log.error("XF WebSocket error", error);
                resultFuture.completeExceptionally(error);
                java.net.http.WebSocket.Listener.super.onError(webSocket, error);
            }
            
            @Override
            public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                log.info("XF WebSocket closed: {} - {}, Final result: '{}'", 
                        statusCode, reason, fullResult.toString());
                if (!resultFuture.isDone()) {
                    String result = fullResult.toString().trim();
                    resultFuture.complete(result.isEmpty() ? "[ASR错误] 未识别到有效文本" : result);
                }
                return java.net.http.WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
            }
        };
        
        // 建立 WebSocket 连接
        log.info("Building XF WebSocket connection...");
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        CompletableFuture<java.net.http.WebSocket> wsFuture = client.newWebSocketBuilder()
                .buildAsync(java.net.URI.create(wsUrl), listener);
        
        log.info("Waiting for XF WebSocket connection...");
        java.net.http.WebSocket webSocket = wsFuture.get(10, TimeUnit.SECONDS);
        log.info("XF WebSocket connection established");
        
        // 等待识别完成或超时
        try {
            log.info("Waiting for final result (timeout: 30s)...");
            String result = resultFuture.get(30, TimeUnit.SECONDS);
            log.info("XF ASR final result: '{}'", result);
            return result;
        } catch (Exception e) {
            log.warn("XF WebSocket recognition timeout, returning partial result: '{}'", fullResult.toString());
            String partialResult = fullResult.toString().trim();
            // 即使超时也返回已收集的结果
            if (!partialResult.isEmpty()) {
                return partialResult;
            }
            return "[ASR错误] 识别超时";
        }
    }

    // 发送音频数据
    private void sendAudioData(java.net.http.WebSocket webSocket, String appid, byte[] audioData) {
        new Thread(() -> {
            try {
                // 连接成功，开始发送数据
                int frameSize = 1280; // 每一帧音频的大小,建议每 40ms 发送 1280B
                int interval = 40;
                int status = 0;  // 音频的状态
                
                log.info("Starting to send audio data, total size: {} bytes", audioData.length);
                
                // 检查音频数据是否为空
                if (audioData.length == 0) {
                    log.warn("Audio data is empty, nothing to send");
                    // 即使没有数据也要发送结束帧
                    sendEndFrame(webSocket);
                    return;
                }
                
                for (int i = 0; i < audioData.length; i += frameSize) {
                    int len = Math.min(frameSize, audioData.length - i);
                    byte[] buffer = java.util.Arrays.copyOfRange(audioData, i, i + len);
                    boolean isLastFrame = (i + len >= audioData.length);
                    
                    switch (status) {
                        case 0:   // 第一帧音频status = 0
                            sendFirstFrame(webSocket, appid, buffer);
                            status = 1;  // 发送完第一帧改变status 为 1
                            break;
                            
                        case 1:  //中间帧status = 1
                            sendContinueFrame(webSocket, buffer);
                            break;
                            
                        case 2:    // 最后一帧音频status = 2 ，标志音频发送结束
                            sendEndFrame(webSocket);
                            log.debug("Sent last frame");
                            return; // 结束发送
                    }
                    
                    // 更新状态
                    if (isLastFrame) {
                        status = 2;  //文件读完，改变status 为 2
                    }
                    
                    // 模拟音频采样延时
                    Thread.sleep(interval);
                }
                
                // 如果循环结束但还没发送最后一帧，则发送最后一帧
                if (status != 2) {
                    sendEndFrame(webSocket);
                    log.debug("Sent final last frame");
                }
                
                log.info("All audio data sent");
            } catch (Exception e) {
                log.error("Error sending audio data", e);
            }
        }).start();
    }
    
    private void sendFirstFrame(java.net.http.WebSocket webSocket, String appid, byte[] buffer) throws Exception {
        java.util.Map<String, Object> frame = new java.util.HashMap<>();
        java.util.Map<String, Object> business = new java.util.HashMap<>();
        java.util.Map<String, Object> common = new java.util.HashMap<>();
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        
        // 填充common
        common.put("app_id", appid);
        
        //填充business
        business.put("language", "zh_cn");
        business.put("domain", "iat");
        business.put("accent", "mandarin");
        business.put("dwa", "wpgs");
        
        //填充data
        data.put("status", 0);
        data.put("format", "audio/L16;rate=16000");
        data.put("encoding", "raw");
        data.put("audio", Base64.getEncoder().encodeToString(buffer));
        
        //填充frame
        frame.put("common", common);
        frame.put("business", business);
        frame.put("data", data);
        
        String firstFrameJson = new ObjectMapper().writeValueAsString(frame);
        webSocket.sendText(firstFrameJson, true);
        log.debug("Sent first frame, size: {} bytes", buffer.length);
    }
    
    private void sendContinueFrame(java.net.http.WebSocket webSocket, byte[] buffer) throws Exception {
        java.util.Map<String, Object> frame = new java.util.HashMap<>();
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("status", 1);
        data.put("format", "audio/L16;rate=16000");
        data.put("encoding", "raw");
        data.put("audio", Base64.getEncoder().encodeToString(buffer));
        frame.put("data", data);
        
        String continueFrameJson = new ObjectMapper().writeValueAsString(frame);
        webSocket.sendText(continueFrameJson, true);
        log.debug("Sent continue frame, size: {} bytes", buffer.length);
    }
    
    private void sendEndFrame(java.net.http.WebSocket webSocket) throws Exception {
        java.util.Map<String, Object> frame = new java.util.HashMap<>();
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("status", 2);
        data.put("audio", "");
        data.put("format", "audio/L16;rate=16000");
        data.put("encoding", "raw");
        frame.put("data", data);
        
        String lastFrameJson = new ObjectMapper().writeValueAsString(frame);
        webSocket.sendText(lastFrameJson, true);
        log.debug("Sent end frame");
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
                String voiceType = (voice != null && !voice.equals("auto")) ? voice : "qiniu_zh_male_qinglin"; // 改为男声
                
                // 构造七牛云TTS请求体 - 根据API文档调整结构
                String requestBody = "{\n" +
                    "  \"request\": {\n" +
                    "    \"text\": \"" + escapeJsonString(text) + "\"\n" +
                    "  },\n" +
                    "  \"audio\": {\n" +
                    "    \"voice_type\": \"" + voiceType + "\",\n" +
                    "    \"encoding\": \"mp3\",\n" +
                    "    \"speed\": 1.2\n" +  // 提高语速到1.2倍
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

    // 生成鉴权URL
    private String getAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        java.net.URL url = new java.net.URL(hostUrl);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());

        StringBuilder builder = new StringBuilder("host: ").append(url.getHost()).append("\n")
                .append("date: ").append(date).append("\n")
                .append("GET ").append(url.getPath()).append(" HTTP/1.1");

        java.nio.charset.Charset charset = java.nio.charset.Charset.forName("UTF-8");
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("hmacsha256");
        javax.crypto.spec.SecretKeySpec spec = new javax.crypto.spec.SecretKeySpec(apiSecret.getBytes(charset), "hmacsha256");
        mac.init(spec);
        byte[] hexDigits = mac.doFinal(builder.toString().getBytes(charset));
        String sha = Base64.getEncoder().encodeToString(hexDigits);

        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"",
                apiKey, "hmac-sha256", "host date request-line", sha);

        String authBase64 = Base64.getEncoder().encodeToString(authorization.getBytes(charset));

        return "https://" + url.getHost() + url.getPath() +
                "?authorization=" + authBase64 +
                "&date=" + java.net.URLEncoder.encode(date, "UTF-8") +
                "&host=" + url.getHost();
    }

    // 将WebM/WAV音频转换为PCM格式
    private byte[] convertToPcm(byte[] audioData, String format) throws Exception {
        if ("webm".equalsIgnoreCase(format)) {
            // 对于WebM格式，需要使用外部工具或者库进行转换
            // 这里简化处理，实际项目中可能需要使用FFmpeg等工具
            log.warn("WebM to PCM conversion is not implemented, using raw data");
            return audioData;
        } else if ("wav".equalsIgnoreCase(format)) {
            // WAV文件包含头部信息，需要提取PCM数据
            return extractPcmFromWav(audioData);
        }
        return audioData;
    }
    
    // 从WAV文件中提取PCM数据
    private byte[] extractPcmFromWav(byte[] wavData) throws IOException {
        // 检查是否是有效的WAV文件
        if (wavData.length < 44) {
            throw new IOException("Invalid WAV file: too small");
        }
        
        // 检查WAV文件头
        String header = new String(java.util.Arrays.copyOfRange(wavData, 0, 4));
        if (!"RIFF".equals(header)) {
            throw new IOException("Invalid WAV file: missing RIFF header");
        }
        
        // 获取数据块的大小和位置
        // 通常WAV格式: RIFF header(4) + file size(4) + WAVE header(4) + fmt chunk(4+4+...) + data chunk header(4+4) + data
        // 我们需要找到"data"块
        int dataOffset = 0;
        for (int i = 0; i < wavData.length - 8; i++) {
            if (wavData[i] == 'd' && wavData[i+1] == 'a' && wavData[i+2] == 't' && wavData[i+3] == 'a') {
                dataOffset = i + 8; // 跳过 "data" 和长度字段 (8字节)
                break;
            }
        }
        
        if (dataOffset == 0) {
            throw new IOException("Invalid WAV file: missing data chunk");
        }
        
        // 提取PCM数据
        byte[] pcmData = new byte[wavData.length - dataOffset];
        System.arraycopy(wavData, dataOffset, pcmData, 0, pcmData.length);
        log.info("Extracted PCM data from WAV: {} bytes (from offset {})", pcmData.length, dataOffset);
        return pcmData;
    }

    // 解析讯飞识别结果
    private String parseXfResult(com.fasterxml.jackson.databind.JsonNode resultNode) {
        try {
            log.debug("Parsing XF result node: {}", resultNode.toPrettyString());
            StringBuilder result = new StringBuilder();
            if (resultNode.has("ws")) {
                com.fasterxml.jackson.databind.JsonNode wsArray = resultNode.get("ws");
                for (int i = 0; i < wsArray.size(); i++) {
                    com.fasterxml.jackson.databind.JsonNode ws = wsArray.get(i);
                    if (ws.has("cw")) {
                        com.fasterxml.jackson.databind.JsonNode cwArray = ws.get("cw");
                        if (cwArray.size() > 0) {
                            com.fasterxml.jackson.databind.JsonNode firstCw = cwArray.get(0);
                            if (firstCw.has("w")) {
                                String word = firstCw.get("w").asText();
                                result.append(word);
                                log.debug("Added word to result: {}", word);
                            }
                        }
                    }
                }
            }
            String finalResult = result.toString();
            log.debug("Parsed XF result: '{}'", finalResult);
            return finalResult;
        } catch (Exception e) {
            log.error("Error parsing XF result", e);
            return "";
        }
    }
}