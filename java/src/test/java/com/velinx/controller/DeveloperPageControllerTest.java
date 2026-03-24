package com.velinx.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeveloperPageController.class)
class DeveloperPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("developer startup test page forwards to static page")
    void startupTestPage_ReturnsOk() throws Exception {
        mockMvc.perform(get("/developer/startup-test"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/developer/startup-test.html"));
    }

    @Test
    @DisplayName("developer settings page forwards to static page")
    void settingsPage_ReturnsOk() throws Exception {
        mockMvc.perform(get("/developer/settings"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/developer/settings/index.html"));
    }

    @Test
    @DisplayName("developer model settings page forwards to static page")
    void modelSettingsPage_ReturnsOk() throws Exception {
        mockMvc.perform(get("/developer/settings/model"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/developer/settings/model.html"));
    }

    @Test
    @DisplayName("developer tts settings page forwards to static page")
    void ttsSettingsPage_ReturnsOk() throws Exception {
        mockMvc.perform(get("/developer/settings/tts"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/developer/settings/tts.html"));
    }
}
