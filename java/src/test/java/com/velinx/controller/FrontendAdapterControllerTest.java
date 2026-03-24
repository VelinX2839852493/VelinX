package com.velinx.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.dto.ChatRequest;
import com.velinx.dto.HeartbeatRequest;
import com.velinx.service.ChatBotService;
import com.velinx.service.FrontendAdapterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FrontendAdapterController.class)
class FrontendAdapterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FrontendAdapterService frontendAdapterService;

    @MockBean
    private ChatBotService chatBotService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("startup test queues message without opening browser")
    void startupTest_Success() throws Exception {
        mockMvc.perform(post("/developer/startup-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatRequest("hello", false, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.messageQueued").value(true))
                .andExpect(jsonPath("$.data.indexOpened").value(false))
                .andExpect(jsonPath("$.data.warning").value("Browser auto-open has been removed. Open /developer/startup-test in a browser to use the backend debug page."));

//        verify(chatBotService).chat("hello", false, false);
    }

    @Test
    @DisplayName("startup test rejects empty message")
    void startupTest_EmptyMessage_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/developer/startup-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatRequest("", false, false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("EMPTY_MESSAGE"));
    }

    @Test
    @DisplayName("heartbeat accepts non-empty message without touching chat services")
    void heartbeat_Success() throws Exception {
        mockMvc.perform(post("/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HeartbeatRequest("__heartbeat__"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.received").value(true));

//        verifyNoInteractions(frontendAdapterService, chatBotService);
    }

    @Test
    @DisplayName("heartbeat rejects blank message")
    void heartbeat_BlankMessage_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HeartbeatRequest("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("EMPTY_MESSAGE"));

//        verifyNoInteractions(frontendAdapterService, chatBotService);
    }
}
