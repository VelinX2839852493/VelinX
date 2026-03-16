package com.velinx.dto;

public record ChatOutcome(boolean success, String code, String message) {
    public static ChatOutcome success(String m) {
        return new ChatOutcome(true, null, m == null ? "" : m);
    }

    public static ChatOutcome failure(String c, String m) {
        return new ChatOutcome(false, c, m == null ? "Unknown failure" : m);
    }
}
