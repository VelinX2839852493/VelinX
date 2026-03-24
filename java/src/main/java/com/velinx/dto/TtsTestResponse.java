package com.velinx.dto;

public record TtsTestResponse(
        boolean success,
        long durationMs,
        String text,
        String error,
        String source
) {
}
