package com.velinx.controller;

import com.velinx.dto.ChatRequest;
import com.velinx.dto.DebugConfigResponse;
import com.velinx.dto.DebugStatusResponse;
import com.velinx.dto.ErrorPayload;
import com.velinx.dto.ErrorResponse;
import com.velinx.dto.ModelConfigPayload;
import com.velinx.dto.SuccessResponse;
import com.velinx.dto.TtsConfigPayload;
import com.velinx.dto.TtsTestRequest;
import com.velinx.dto.TtsTestResponse;
import com.velinx.service.ChatBotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/bot")
public class WorkBotController {

    private final ChatBotService chatBotService;

    public WorkBotController(ChatBotService chatBotService) {
        this.chatBotService = chatBotService;
    }

    @PostMapping("/review/resolve")
    public ResponseEntity<String> resolveReview(@RequestParam boolean approved) {
        chatBotService.resolveReview(approved);
        return ResponseEntity.ok(approved ? "approved" : "rejected");
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream() {
        return chatBotService.subscribe();
    }

    @PostMapping("/send")
    public String sendMessage(@RequestParam String message) {
        chatBotService.chat(message);
        return "success";
    }

    @PostMapping(path = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> sendMessage(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            return ResponseEntity.badRequest().body("message is required");
        }

        chatBotService.chat(message, request.captureDesktop(), request.tts());
        return ResponseEntity.ok("success");
    }

    @PostMapping("/config")
    public ResponseEntity<String> updateConfig(@RequestBody Map<String, String> config) {
        try {
            chatBotService.rebuildBot(config.get("baseUrl"), config.get("apiKey"), config.get("modelName"));
            return ResponseEntity.ok("config updated");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/debug/status")
    public ResponseEntity<SuccessResponse<DebugStatusResponse>> debugStatus() {
        return ResponseEntity.ok(success(chatBotService.getDebugStatus()));
    }

    @GetMapping("/debug/config")
    public ResponseEntity<SuccessResponse<DebugConfigResponse>> debugConfig() {
        return ResponseEntity.ok(success(chatBotService.getDebugConfig()));
    }

    @PostMapping(path = "/debug/config/model", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateModelConfig(@RequestBody(required = false) ModelConfigPayload request) {
        String baseUrl = normalize(request == null ? null : request.baseUrl());
        String apiKey = normalize(request == null ? null : request.apiKey());
        String modelName = normalize(request == null ? null : request.modelName());

        if (baseUrl == null || apiKey == null || modelName == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MODEL_CONFIG", "baseUrl, apiKey, and modelName are required");
        }

        try {
            chatBotService.rebuildBot(baseUrl, apiKey, modelName);
            return ResponseEntity.ok(success(chatBotService.getDebugConfig().model()));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_CONFIG_SAVE_FAILED", e.getMessage());
        }
    }

    @PostMapping(path = "/debug/config/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateTtsConfig(@RequestBody(required = false) TtsConfigPayload request) {
        String apiUri = normalize(request == null ? null : request.apiUri());
        String apiKey = normalize(request == null ? null : request.apiKey());
        String model = normalize(request == null ? null : request.model());
        String voice = normalize(request == null ? null : request.voice());

        if (apiUri == null || apiKey == null || model == null || voice == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_TTS_CONFIG", "apiUri, apiKey, model, and voice are required");
        }

        try {
            chatBotService.rebuildTtsClient(apiUri, apiKey, model, voice);
            return ResponseEntity.ok(success(chatBotService.getDebugConfig().tts()));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "TTS_CONFIG_SAVE_FAILED", e.getMessage());
        }
    }

    @PostMapping(path = "/debug/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<TtsTestResponse>> testTts(@RequestBody(required = false) TtsTestRequest request) {
        return ResponseEntity.ok(success(chatBotService.testTts(request == null ? null : request.text())));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private <T> SuccessResponse<T> success(T data) {
        return new SuccessResponse<>(true, data);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(false, new ErrorPayload(code, message)));
    }
}
