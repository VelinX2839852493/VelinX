package com.velinx.core.memory.store;

import com.velinx.core.memory.ShortChatHistoryStore;
import com.velinx.core.memory.ChatMessageTextExtractor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ShortTermMemory implements ShortChatHistoryStore {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermMemory.class);

    private final List<ChatMessage> allMessages = new ArrayList<>();
    private final Path shortTermStorePath;

    public ShortTermMemory(Path shortTermStorePath) {
        this.shortTermStorePath = shortTermStorePath;
        loadShortTermMemory();
    }

    @Override
    public List<ChatMessage> getAllMessages() {
        return allMessages;
    }

    @Override
    public void add(ChatMessage message) {
        ChatMessage historySafeMessage = ChatMessageTextExtractor.sanitizeForHistory(message);
        if (historySafeMessage == null) {
            return;
        }

        if (historySafeMessage instanceof SystemMessage) {
            allMessages.removeIf(existing -> existing instanceof SystemMessage);
            allMessages.add(0, historySafeMessage);
            return;
        }
        allMessages.add(historySafeMessage);
        saveShortTermMemory();
    }

    @Override
    public void clear() {
        allMessages.clear();
        saveShortTermMemory();
    }

    @Override
    public void refresh(List<ChatMessage> kept) {
        allMessages.clear();
        kept.stream()
                .map(ChatMessageTextExtractor::sanitizeForHistory)
                .filter(Objects::nonNull)
                .forEach(allMessages::add);
        saveShortTermMemory();
    }

    @Override
    public String getConversationHistoryAsPrompt() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : allMessages) {
            if (msg instanceof UserMessage) {
                sb.append("User: ").append(ChatMessageTextExtractor.extract(msg)).append("\n");
            } else if (msg instanceof AiMessage aiMessage && aiMessage.text() != null) {
                sb.append("AI: ").append(aiMessage.text()).append("\n");
            }
        }
        return sb.toString();
    }

    private void loadShortTermMemory() {
        List<ChatMessage> history = new ArrayList<>();
        boolean sanitized = false;
        try {
            if (Files.exists(shortTermStorePath)) {
                String json = Files.readString(shortTermStorePath);
                if (!json.isBlank()) {
                    for (ChatMessage message : ChatMessageDeserializer.messagesFromJson(json)) {
                        ChatMessage historySafeMessage = ChatMessageTextExtractor.sanitizeForHistory(message);
                        if (historySafeMessage == null) {
                            sanitized = true;
                            continue;
                        }
                        if (historySafeMessage != message) {
                            sanitized = true;
                        }
                        history.add(historySafeMessage);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load short-term memory", e);
        }
        this.allMessages.addAll(history);
        if (sanitized) {
            saveShortTermMemory();
        }
    }

    @Override
    public void saveShortTermMemory() {
        try {
            List<ChatMessage> toSave = allMessages.stream()
                    .map(ChatMessageTextExtractor::sanitizeForHistory)
                    .filter(Objects::nonNull)
                    .filter(message -> !(message instanceof SystemMessage))
                    .toList();
            Files.writeString(shortTermStorePath, ChatMessageSerializer.messagesToJson(toSave));
        } catch (Exception e) {
            logger.error("Failed to save short-term memory", e);
        }
    }
}
