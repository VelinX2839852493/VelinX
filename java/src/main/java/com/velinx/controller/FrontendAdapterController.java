package com.velinx.controller;

import com.velinx.dto.ChatOutcome;
import com.velinx.dto.ChatRequest;
import com.velinx.dto.ErrorPayload;
import com.velinx.dto.ErrorResponse;
import com.velinx.dto.HeartbeatRequest;
import com.velinx.dto.frontendadapter.SessionStartRequest;
import com.velinx.dto.SuccessResponse;
import com.velinx.service.ChatBotService;
import com.velinx.service.FrontendAdapterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FrontendAdapterController {

    private final FrontendAdapterService frontendAdapterService;
    private final ChatBotService chatBotService;

    public FrontendAdapterController(
            FrontendAdapterService frontendAdapterService,
            ChatBotService chatBotService
    ) {
        this.frontendAdapterService = frontendAdapterService;
        this.chatBotService = chatBotService;
    }

    @GetMapping("/health")
    public ResponseEntity<SuccessResponse<Map<String, String>>> health() {
        return ResponseEntity.ok(success(frontendAdapterService.health()));
    }

    @PostMapping(path = "/session/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startSession(@RequestBody(required = false) SessionStartRequest request) {
        try {
            String botName = frontendAdapterService.startSession(request);
            return ResponseEntity.ok(success(Map.of("botName", botName)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
        }
    }

    @PostMapping(path = "/session/stop")
    public ResponseEntity<?> stopSession() {
        try {
            frontendAdapterService.stopSession();
            return ResponseEntity.ok(success(Map.of("stopped", true)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "STOP_FAILED", e.getMessage());
        }
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(@RequestBody(required = false) ChatRequest request) {
        ChatOutcome outcome = frontendAdapterService.chat(request);

        if (!outcome.success()) {
            HttpStatus status = switch (outcome.code()) {
                case "CHAT_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                case "EMPTY_TEXT", "SESSION_NOT_STARTED" -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            return error(status, outcome.code(), outcome.message());
        }

        return ResponseEntity.ok(success(Map.of("content", outcome.message())));
    }

    @PostMapping(path = "/heartbeat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> heartbeat(@RequestBody(required = false) HeartbeatRequest request) {
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "message is required");
        }

        message = "这是你看到的";
        ChatOutcome outcome = frontendAdapterService.chat(new ChatRequest(message,true,true));

        return ResponseEntity.ok(success(Map.of("received", true)));
    }

    @PostMapping(path = "/developer/startup-test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startupTest(@RequestBody(required = false) ChatRequest request) {
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "message is required");
        }

        try {
            chatBotService.chat(message, false, false);

            return ResponseEntity.ok(success(new StartupTestResponse(
                    true,
                    false,
                    null,
                    "Browser auto-open has been removed. Open /developer/startup-test in a browser to use the backend debug page."
            )));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "STARTUP_TEST_FAILED", e.getMessage());
        }
    }

    private <T> SuccessResponse<T> success(T data) {
        return new SuccessResponse<>(true, data);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(new ErrorResponse(false, new ErrorPayload(code, msg)));
    }

    private record StartupTestResponse(
            boolean messageQueued,
            boolean indexOpened,
            String indexUrl,
            String warning
    ) {
    }
}
