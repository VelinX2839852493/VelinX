package com.velinx.core.memory;

import com.velinx.core.memory.fact.FactCardExtractor;
import com.velinx.core.memory.store.ShortTermMemory;
import com.velinx.core.memory.summary.Summarizer;
import com.velinx.core.platform.config.ConfigManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeToolMessagesOnlyDuringActiveTurn() {
        ShortTermMemory shortTermMemory = new ShortTermMemory(tempDir.resolve("short_term_memory.json"));
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        MemoryManager memoryManager = createMemoryManager(shortTermMemory, executor);

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("grep")
                .arguments("{}")
                .build();
        AiMessage toolCallMessage = AiMessage.from(List.of(toolRequest));
        ToolExecutionResultMessage toolResultMessage = ToolExecutionResultMessage.from(toolRequest, "internal tool output");
        AiMessage finalMessage = AiMessage.from("Final assistant reply");

        memoryManager.prepareTurn("Find matches");
        memoryManager.add(UserMessage.from("Find matches"));
        memoryManager.add(toolCallMessage);
        memoryManager.add(toolResultMessage);
        memoryManager.add(finalMessage);

        List<ChatMessage> inFlightMessages = memoryManager.messages();
        assertTrue(inFlightMessages.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage));
        assertTrue(inFlightMessages.stream().anyMatch(message ->
                message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()));

        memoryManager.commitTurn();

        List<ChatMessage> committedMessages = shortTermMemory.getAllMessages();
        assertEquals(2, committedMessages.size());
        assertInstanceOf(UserMessage.class, committedMessages.get(0));
        assertEquals("Find matches", ChatMessageTextExtractor.extract(committedMessages.get(0)));
        assertInstanceOf(AiMessage.class, committedMessages.get(1));
        assertEquals("Final assistant reply", ChatMessageTextExtractor.extract(committedMessages.get(1)));

        List<ChatMessage> finalMessages = memoryManager.messages();
        assertFalse(finalMessages.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage));
        assertFalse(finalMessages.stream().anyMatch(message ->
                message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()));
    }

    @Test
    void abortTurnShouldDiscardInFlightMessages() {
        ShortTermMemory shortTermMemory = new ShortTermMemory(tempDir.resolve("short_term_memory.json"));
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        MemoryManager memoryManager = createMemoryManager(shortTermMemory, executor);

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("grep")
                .arguments("{}")
                .build();

        memoryManager.prepareTurn("Find matches");
        memoryManager.add(UserMessage.from("Find matches"));
        memoryManager.add(AiMessage.from(List.of(toolRequest)));
        memoryManager.add(ToolExecutionResultMessage.from(toolRequest, "internal tool output"));
        memoryManager.abortTurn();

        assertTrue(shortTermMemory.getAllMessages().isEmpty());
        assertTrue(memoryManager.messages().isEmpty());
    }

    @Test
    void afterTurnShouldIgnoreBlankResponses() {
        ShortTermMemory shortTermMemory = new ShortTermMemory(tempDir.resolve("short_term_memory.json"));
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        MemoryManager memoryManager = createMemoryManager(shortTermMemory, executor);

        memoryManager.afterTurn("hello", "   ", mock(ChatLanguageModel.class));

        verify(executor, never()).execute(any(Runnable.class));
    }

    private MemoryManager createMemoryManager(ShortTermMemory shortTermMemory, ExecutorService executor) {
        MemoryFeatures features = new MemoryFeatures();
        features.setEnableSummary(false);
        features.setEnableFactExtraction(false);
        features.setEnableProfileUpdate(false);
        features.setEnableKnowledgeRetrieval(false);
        features.setEnableLongTermRetrieval(false);

        return new MemoryManager(
                features,
                shortTermMemory,
                new Summarizer(inMemorySummaryRepository()),
                mock(HippocampusManager.class),
                mock(KnowledgeBase.class),
                mock(FactCardExtractor.class),
                mock(ProfileManager.class),
                mock(ChatLanguageModel.class),
                mock(ConfigManager.class),
                executor
        );
    }

    private SummaryRepository inMemorySummaryRepository() {
        return new SummaryRepository() {
            private String summary = "";

            @Override
            public String load() {
                return summary;
            }

            @Override
            public void save(String summary) {
                this.summary = summary;
            }
        };
    }
}
