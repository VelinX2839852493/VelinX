package com.velinx.core.chat.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranscriptLogger {

    private static final Logger logger = LoggerFactory.getLogger("com.velinx.chat.transcript");

    private ChatTranscriptLogger() {
    }

    public static void user(String text, boolean captureDesktop) {
        log(captureDesktop ? "USER+DESKTOP" : "USER", text);
    }

    public static void ai(String text) {
        log("AI", text);
    }

    public static void system(String text) {
        log("SYSTEM", text);
    }

    private static void log(String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        logger.info("[{}]\n{}", role, text.strip());
    }
}
