package com.velinx.dto;

public record DebugStatusResponse(
        boolean serviceHealthy,
        boolean initialized,
        boolean botReady,
        boolean ttsReady,
        ModelConfigSummary model,
        TtsConfigSummary tts
) {
}
