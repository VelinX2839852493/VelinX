package com.velinx.core.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.stream.Collectors;

public final class ChatMessageTextExtractor {

    private ChatMessageTextExtractor() {
    }

    public static String extract(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return extract(userMessage);
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text() == null ? "" : aiMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
            return toolExecutionResultMessage.text();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        return "";
    }

    public static String extract(UserMessage userMessage) {
        return userMessage.contents().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .collect(Collectors.joining("\n"));
    }

    public static boolean hasImage(ChatMessage message) {
        return message instanceof UserMessage userMessage && hasImage(userMessage);
    }

    public static boolean hasImage(UserMessage userMessage) {
        return userMessage.contents().stream().anyMatch(ImageContent.class::isInstance);
    }

    public static ChatMessage sanitizeForHistory(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return sanitizeForHistory(userMessage);
        }
        if (message instanceof AiMessage aiMessage) {
            return sanitizeForHistory(aiMessage);
        }
        if (message instanceof ToolExecutionResultMessage) {
            return null;
        }
        return message;
    }

    public static UserMessage sanitizeForHistory(UserMessage userMessage) {
        if (!hasImage(userMessage)) {
            return userMessage;
        }

        String text = extract(userMessage);
        if (text.isBlank()) {
            return null;
        }
        return UserMessage.from(text);
    }

    public static AiMessage sanitizeForHistory(AiMessage aiMessage) {
        if (aiMessage.hasToolExecutionRequests()) {
            return null;
        }

        String text = aiMessage.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        return aiMessage;
    }
}
