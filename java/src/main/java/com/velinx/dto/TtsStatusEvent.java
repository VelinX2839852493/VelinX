package com.velinx.dto;

public record TtsStatusEvent(
        boolean success,
        long durationMs,
        String text,
        String error,
        String source
) {
}
