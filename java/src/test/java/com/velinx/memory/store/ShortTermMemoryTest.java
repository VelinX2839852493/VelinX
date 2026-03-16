package com.velinx.memory.store;

import com.velinx.core.memory.ChatMessageTextExtractor;
import com.velinx.core.memory.store.ShortTermMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortTermMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStripImagePayloadWhenLoadingExistingHistory() throws Exception {
        Path storePath = tempDir.resolve("short_term_memory.json");
        UserMessage multimodalMessage = UserMessage.from(
                TextContent.from("Look at this"),
                ImageContent.from("dGVzdA==", "image/png", ImageContent.DetailLevel.HIGH)
        );
        Files.writeString(storePath, ChatMessageSerializer.messagesToJson(List.of(multimodalMessage)));

        ShortTermMemory memory = new ShortTermMemory(storePath);

        assertEquals(1, memory.getAllMessages().size());
        ChatMessage storedMessage = memory.getAllMessages().get(0);
        assertInstanceOf(UserMessage.class, storedMessage);
        assertEquals("Look at this", ChatMessageTextExtractor.extract(storedMessage));
        assertFalse(ChatMessageTextExtractor.hasImage(storedMessage));

        String sanitizedJson = Files.readString(storePath);
        assertTrue(sanitizedJson.contains("Look at this"));
        assertFalse(sanitizedJson.contains("dGVzdA=="));
    }

    @Test
    void shouldIgnoreImageOnlyMessagesInHistory() {
        Path storePath = tempDir.resolve("short_term_memory.json");
        ShortTermMemory memory = new ShortTermMemory(storePath);

        memory.add(UserMessage.from(
                ImageContent.from("dGVzdA==", "image/png", ImageContent.DetailLevel.HIGH)
        ));

        assertTrue(memory.getAllMessages().isEmpty());
        assertFalse(Files.exists(storePath));
    }

    @Test
    void shouldPersistOnlyTextWhenAddingMultimodalMessage() throws Exception {
        Path storePath = tempDir.resolve("short_term_memory.json");
        ShortTermMemory memory = new ShortTermMemory(storePath);

        memory.add(UserMessage.from(
                TextContent.from("Please analyze this"),
                ImageContent.from("dGVzdA==", "image/png", ImageContent.DetailLevel.HIGH)
        ));

        assertEquals(1, memory.getAllMessages().size());
        ChatMessage storedMessage = memory.getAllMessages().get(0);
        assertEquals("Please analyze this", ChatMessageTextExtractor.extract(storedMessage));
        assertFalse(ChatMessageTextExtractor.hasImage(storedMessage));

        String persistedJson = Files.readString(storePath);
        assertTrue(persistedJson.contains("Please analyze this"));
        assertFalse(persistedJson.contains("dGVzdA=="));
    }
}
