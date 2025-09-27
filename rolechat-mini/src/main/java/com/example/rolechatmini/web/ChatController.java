package com.example.rolechatmini.web;

import com.example.rolechatmini.service.OpenAICompatClient;
import com.example.rolechatmini.service.RoleCatalogService;
import com.example.rolechatmini.service.SkillOrchestrator;
import com.example.rolechatmini.web.dto.ChatDtos.AsrUploadRequest;
import com.example.rolechatmini.web.dto.ChatDtos.ChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RoleCatalogService roleCatalogService;
    private final SkillOrchestrator skills;
    private final OpenAICompatClient openAI;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid ChatRequest req) throws Exception {
        String userText = req.getUserText();
        if ((userText == null || userText.isBlank()) && req.getAudioBase64() != null) {
            userText = openAI.asrTranscribeBase64(req.getAudioBase64(), "wav");
        }
        var role = roleCatalogService.getById(req.getRoleId());
        var pb = skills.build(role, userText);
        String combined = String.join("\n- ", pb.assistantGuidelines());
        String system = pb.systemMessage() + "\n\n助手指引:\n- " + combined;
        SseEmitter emitter = new SseEmitter(60_000L);
        openAI.streamChat(system, userText, emitter);
        return emitter;
    }

    @PostMapping("/asr")
    public Object asr(@RequestBody @Valid AsrUploadRequest req) {
        String text = openAI.asrTranscribeBase64(req.getAudioBase64(), req.getFormat());
        return java.util.Map.of("text", text);
    }

//    @PostMapping("/tts")
//    public Object tts(@RequestBody @Valid TtsRequest req) {
//        String b64 = openAI.ttsSynthesizeBase64(req.getText(), req.getVoice());
//        return java.util.Map.of("audioBase64", b64);
//    }
}
