package com.velinx.controller;

import com.velinx.core.chat.model.ChatSendRequest;
import com.velinx.dto.ChatRequest;
import com.velinx.service.ChatBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(WorkBotController.class);

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

        chatBotService.chat(message, request.captureDesktop(),request.tts());
        return ResponseEntity.ok("success");
    }

    @PostMapping("/config")
    public ResponseEntity<String> updateConfig(@RequestBody Map<String, String> config) {
        chatBotService.rebuildBot(config.get("baseUrl"), config.get("apiKey"), config.get("modelName"));
        return ResponseEntity.ok("config updated");
    }
}
