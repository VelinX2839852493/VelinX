package com.velinx.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.dto.ChatRequest;
import com.velinx.service.ChatBotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkBotController.class) // 只加载 Web 层，不启动完整容器，速度快
class WorkBotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatBotService chatBotService; // 模拟 Service 层

    @Autowired
    private ObjectMapper objectMapper; // 用于将对象转为 JSON 字符串

    @Test
    @DisplayName("测试发送 JSON 格式的消息 - 成功")
    void sendMessage_Json_Success() throws Exception {
        // 准备请求数据
        // 注意：这里的 ChatRequest 需要有对应的构造函数或 Builder
        ChatRequest request = new ChatRequest("Hello Bot", true, true);

        mockMvc.perform(post("/api/bot/send")
                        .contentType(MediaType.APPLICATION_JSON) // 指定内容类型
                        .content(objectMapper.writeValueAsString(request))) // 将对象转为 JSON
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        // 验证 Service 层的方法是否被正确调用
        verify(chatBotService).chat("Hello Bot", true, true);
    }

    @Test
    @DisplayName("测试发送空消息 - 应该返回 400")
    void sendMessage_EmptyMessage_ReturnsBadRequest() throws Exception {
        ChatRequest request = new ChatRequest("", true, false);

        mockMvc.perform(post("/api/bot/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("message is required"));
    }

    @Test
    @DisplayName("测试解审批操作 - RequestParam 方式")
    void resolveReview_Success() throws Exception {
        mockMvc.perform(post("/api/bot/review/resolve")
                        .param("approved", "true")) // 测试 @RequestParam
                .andExpect(status().isOk())
                .andExpect(content().string("approved"));

        verify(chatBotService).resolveReview(true);
    }

    @Test
    @DisplayName("测试配置更新 - JSON Map 方式")
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
    @DisplayName("测试 SSE 流订阅")
    void stream_Success() throws Exception {
        mockMvc.perform(get("/api/bot/stream"))
                .andExpect(status().isOk());

        verify(chatBotService).subscribe();
    }
}