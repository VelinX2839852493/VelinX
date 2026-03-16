package com.velinx.core.memory.model;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.time.Instant;

/**
 * 消息
 * @param turnId
 * @param userInput
 * @param aiResponse
 * @param model
 * @param enqueuedAt
 */
public record CompletedTurn(long turnId,
                            String userInput,
                            String aiResponse,
                            ChatLanguageModel model,
                            Instant enqueuedAt) {
}
