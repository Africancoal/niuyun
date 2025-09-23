package com.example.rolechatmini.service;

import com.example.rolechatmini.config.AppProperties;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAICompatClient {
    private final AppProperties props;

    public void streamChat(String system, String user, SseEmitter emitter) throws Exception {
        String url = props.getLlm().getBaseUrl().endsWith("/") ? props.getLlm().getBaseUrl() + "chat/completions" : props.getLlm().getBaseUrl() + "/chat/completions";
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
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            client.execute(post, response -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
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
        // 演示：直接返回占位文本（实际应调用Whisper/兼容接口）
        return "[ASR占位] 用户语音内容（请配置实际ASR服务）";
    }

    public String ttsSynthesizeBase64(String text, String voice) {
        // 演示：返回伪base64（实际应调用TTS服务返回音频）
        return Base64.getEncoder().encodeToString(("FakeAudio:" + text + "(" + voice + ")").getBytes(StandardCharsets.UTF_8));
    }

    private String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
