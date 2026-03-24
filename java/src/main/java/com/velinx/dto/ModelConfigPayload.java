package com.velinx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConfigPayload(String baseUrl, String apiKey, String modelName) {
}
