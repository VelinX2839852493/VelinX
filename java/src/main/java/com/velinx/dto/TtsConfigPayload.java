package com.velinx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TtsConfigPayload(String apiUri, String apiKey, String model, String voice) {
}
