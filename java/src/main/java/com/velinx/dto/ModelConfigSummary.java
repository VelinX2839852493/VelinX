package com.velinx.dto;

public record ModelConfigSummary(
        boolean configured,
        boolean ready,
        String baseUrl,
        String modelName,
        boolean hasApiKey,
        String apiKeyMasked
) {
}
