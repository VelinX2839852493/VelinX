package com.velinx.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.dto.ChatRequest;
import com.velinx.dto.DebugConfigResponse;
import com.velinx.dto.DebugStatusResponse;
import com.velinx.dto.ModelConfigPayload;
import com.velinx.dto.ModelConfigSummary;
import com.velinx.dto.TtsConfigPayload;
import com.velinx.dto.TtsConfigSummary;
import com.velinx.dto.TtsTestRequest;
import com.velinx.dto.TtsTestResponse;
import com.velinx.service.ChatBotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkBotController.class)
class WorkBotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatBotService chatBotService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("send json message succeeds")
    void sendMessage_Json_Success() throws Exception {
        ChatRequest request = new ChatRequest("Hello Bot", true, true);

        mockMvc.perform(post("/api/bot/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        verify(chatBotService).chat("Hello Bot", true, true);
    }

    @Test
    @DisplayName("send empty message returns bad request")
    void sendMessage_EmptyMessage_ReturnsBadRequest() throws Exception {
        ChatRequest request = new ChatRequest("", true, false);

        mockMvc.perform(post("/api/bot/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("message is required"));
    }

    @Test
    @DisplayName("resolve review succeeds")
    void resolveReview_Success() throws Exception {
        mockMvc.perform(post("/api/bot/review/resolve")
                        .param("approved", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("approved"));

        verify(chatBotService).resolveReview(true);
    }

    @Test
    @DisplayName("legacy config update delegates to bot rebuild")
    void updateConfig_Success() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("baseUrl", "http://api.test.com");
        config.put("apiKey", "sk-123");
        config.put("modelName", "gpt-4");

        mockMvc.perform(post("/api/bot/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk())
                .andExpect(content().string("config updated"));

        verify(chatBotService).rebuildBot("http://api.test.com", "sk-123", "gpt-4");
    }

    @Test
    @DisplayName("stream subscribes to sse emitter")
    void stream_Success() throws Exception {
        when(chatBotService.subscribe()).thenReturn(new SseEmitter(0L));

        mockMvc.perform(get("/api/bot/stream"))
                .andExpect(status().isOk());

        verify(chatBotService).subscribe();
    }

    @Test
    @DisplayName("debug status returns summary payload")
    void debugStatus_Success() throws Exception {
        when(chatBotService.getDebugStatus()).thenReturn(new DebugStatusResponse(
                true,
                true,
                true,
                true,
                new ModelConfigSummary(true, true, "https://api.example.com", "gpt-4o-mini", true, "sk-1...1234"),
                new TtsConfigSummary(true, true, "https://tts.example.com", "tts-model", "claire", true, "sk-2...5678")
        ));

        mockMvc.perform(get("/api/bot/debug/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.serviceHealthy").value(true))
                .andExpect(jsonPath("$.data.model.modelName").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.data.tts.voice").value("claire"));
    }

    @Test
    @DisplayName("debug config returns current effective config")
    void debugConfig_Success() throws Exception {
        when(chatBotService.getDebugConfig()).thenReturn(new DebugConfigResponse(
                new ModelConfigPayload("https://api.example.com", "sk-model", "gpt-4o"),
                new TtsConfigPayload("https://tts.example.com", "sk-tts", "tts-model", "claire")
        ));

        mockMvc.perform(get("/api/bot/debug/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.model.baseUrl").value("https://api.example.com"))
                .andExpect(jsonPath("$.data.tts.voice").value("claire"));
    }

    @Test
    @DisplayName("saving invalid model config returns bad request")
    void updateModelConfig_Invalid_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/bot/debug/config/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ModelConfigPayload("", "sk", "gpt-4o"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_MODEL_CONFIG"));
    }

    @Test
    @DisplayName("saving model config rebuilds bot and returns saved config")
    void updateModelConfig_Success() throws Exception {
        when(chatBotService.getDebugConfig()).thenReturn(new DebugConfigResponse(
                new ModelConfigPayload("https://api.example.com", "sk-model", "gpt-4o"),
                new TtsConfigPayload("https://tts.example.com", "sk-tts", "tts-model", "claire")
        ));

        mockMvc.perform(post("/api/bot/debug/config/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ModelConfigPayload("https://api.example.com", "sk-model", "gpt-4o"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.modelName").value("gpt-4o"));

        verify(chatBotService).rebuildBot("https://api.example.com", "sk-model", "gpt-4o");
    }

    @Test
    @DisplayName("saving invalid tts config returns bad request")
    void updateTtsConfig_Invalid_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/bot/debug/config/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TtsConfigPayload("https://tts.example.com", "", "tts-model", "claire"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TTS_CONFIG"));
    }

    @Test
    @DisplayName("saving tts config rebuilds client and returns saved config")
    void updateTtsConfig_Success() throws Exception {
        when(chatBotService.getDebugConfig()).thenReturn(new DebugConfigResponse(
                new ModelConfigPayload("https://api.example.com", "sk-model", "gpt-4o"),
                new TtsConfigPayload("https://tts.example.com", "sk-tts", "tts-model", "claire")
        ));

        mockMvc.perform(post("/api/bot/debug/config/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TtsConfigPayload("https://tts.example.com", "sk-tts", "tts-model", "claire"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.voice").value("claire"));

        verify(chatBotService).rebuildTtsClient("https://tts.example.com", "sk-tts", "tts-model", "claire");
    }

    @Test
    @DisplayName("tts smoke test returns explicit success or failure payload")
    void testTts_Success() throws Exception {
        when(chatBotService.testTts("hello world")).thenReturn(new TtsTestResponse(true, 123L, "hello world", null, "debug-tts"));

        mockMvc.perform(post("/api/bot/debug/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TtsTestRequest("hello world"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.durationMs").value(123))
                .andExpect(jsonPath("$.data.source").value("debug-tts"));
    }
}
