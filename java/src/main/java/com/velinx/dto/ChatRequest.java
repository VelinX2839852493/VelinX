package com.velinx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(String message, boolean captureDesktop,boolean tts) {
}
