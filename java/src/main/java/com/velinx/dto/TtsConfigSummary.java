package com.velinx.dto;

public record TtsConfigSummary(
        boolean configured,
        boolean ready,
        String apiUri,
        String model,
        String voice,
        boolean hasApiKey,
        String apiKeyMasked
) {
}
