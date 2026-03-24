package com.velinx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.core.TTS.TtsClient;
import com.velinx.core.chat.runtime.BotResponseListener;
import com.velinx.core.chat.runtime.ChatBot;
import com.velinx.core.platform.config.BotConfigStore;
import com.velinx.core.platform.config.TtsConfigStore;
import com.velinx.dto.DebugConfigResponse;
import com.velinx.dto.TtsStatusEvent;
import com.velinx.dto.TtsTestResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatBotServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("debug config reads current model and tts file contents")
    void getDebugConfig_ReadsCurrentFileContents() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://api.example.com", "sk-model", "gpt-4o",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        DebugConfigResponse response = runtime.service().getDebugConfig();

        assertEquals("https://api.example.com", response.model().baseUrl());
        assertEquals("sk-model", response.model().apiKey());
        assertEquals("gpt-4o", response.model().modelName());
        assertEquals("https://tts.example.com", response.tts().apiUri());
        assertEquals("sk-tts", response.tts().apiKey());
        assertEquals("tts-model", response.tts().model());
        assertEquals("claire", response.tts().voice());
    }

    @Test
    @DisplayName("debug config normalizes OpenRouter endpoint URLs to the SDK base URL")
    void getDebugConfig_NormalizesOpenRouterEndpointUrl() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://openrouter.ai/api/v1/chat/completions", "sk-model", "minimax/minimax-m2.5:free",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        DebugConfigResponse response = runtime.service().getDebugConfig();

        assertEquals("https://openrouter.ai/api/v1", response.model().baseUrl());
    }

    @Test
    @DisplayName("saving model config updates file and rebuilds bot runtime")
    void rebuildBot_UpdatesFileAndRebuildsRuntime() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://old.example.com", "sk-old", "old-model",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        ChatBot firstBot = mock(ChatBot.class);
        ChatBot secondBot = mock(ChatBot.class);
        runtime.enqueueChatBot(firstBot);
        runtime.enqueueChatBot(secondBot);
        runtime.enqueueTtsClient(mock(TtsClient.class));
        runtime.enqueueTtsClient(mock(TtsClient.class));

        runtime.service().init();
        runtime.service().rebuildBot("https://new.example.com", "sk-new", "new-model");

        DebugConfigResponse response = runtime.service().getDebugConfig();
        assertEquals("https://new.example.com", response.model().baseUrl());
        assertEquals("sk-new", response.model().apiKey());
        assertEquals("new-model", response.model().modelName());
        assertEquals(2, runtime.chatBotCreateCount());
        verify(firstBot).stop();
    }

    @Test
    @DisplayName("saving model config strips chat completion endpoint before persisting")
    void rebuildBot_NormalizesEndpointBeforePersisting() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://old.example.com", "sk-old", "old-model",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        runtime.enqueueChatBot(mock(ChatBot.class));
        runtime.enqueueChatBot(mock(ChatBot.class));
        runtime.enqueueTtsClient(mock(TtsClient.class));
        runtime.enqueueTtsClient(mock(TtsClient.class));

        runtime.service().init();
        runtime.service().rebuildBot("https://openrouter.ai/api/v1/chat/completions", "sk-new", "new-model");

        String persistedConfig = Files.readString(runtime.modelConfigPath());
        assertTrue(persistedConfig.contains("https://openrouter.ai/api/v1"));
        assertFalse(persistedConfig.contains("chat/completions"));
    }

    @Test
    @DisplayName("saving tts config updates file and rebuilds tts client only")
    void rebuildTtsClient_UpdatesFileAndRebuildsTtsClient() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://api.example.com", "sk-model", "gpt-4o",
                "https://tts-old.example.com", "sk-old", "old-tts", "old-voice");

        runtime.enqueueChatBot(mock(ChatBot.class));
        runtime.enqueueTtsClient(mock(TtsClient.class));
        runtime.enqueueTtsClient(mock(TtsClient.class));

        runtime.service().init();
        runtime.service().rebuildTtsClient("https://tts-new.example.com", "sk-new", "new-tts", "new-voice");

        DebugConfigResponse response = runtime.service().getDebugConfig();
        assertEquals("https://tts-new.example.com", response.tts().apiUri());
        assertEquals("sk-new", response.tts().apiKey());
        assertEquals("new-tts", response.tts().model());
        assertEquals("new-voice", response.tts().voice());
        assertEquals(1, runtime.chatBotCreateCount());
        assertEquals(2, runtime.ttsClientCreateCount());
    }

    @Test
    @DisplayName("tts smoke test returns explicit error instead of only logging")
    void testTts_ReturnsExplicitError() throws Exception {
        TestRuntime runtime = createRuntime();
        writeConfigFiles(runtime.modelConfigPath(), runtime.ttsConfigPath(),
                "https://api.example.com", "sk-model", "gpt-4o",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        runtime.enqueueChatBot(mock(ChatBot.class));
        TtsClient failingClient = mock(TtsClient.class);
        doThrow(new IllegalStateException("tts boom")).when(failingClient).speak("hello");
        runtime.enqueueTtsClient(failingClient);

        runtime.service().init();
        TtsTestResponse result = runtime.service().testTts("hello");

        assertFalse(result.success());
        assertTrue(result.error().contains("tts boom"));
        assertEquals(1, runtime.ttsEvents().size());
        assertFalse(runtime.ttsEvents().getFirst().success());
    }

    @Test
    @DisplayName("tts-status event is emitted for both success and failure during bot playback")
    void speakAsync_EmitsTtsStatusForSuccessAndFailure() throws Exception {
        TestRuntime successRuntime = createRuntime();
        writeConfigFiles(successRuntime.modelConfigPath(), successRuntime.ttsConfigPath(),
                "https://api.example.com", "sk-model", "gpt-4o",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        successRuntime.enqueueChatBot(mock(ChatBot.class));
        successRuntime.enqueueTtsClient(mock(TtsClient.class));
        successRuntime.service().init();
        successRuntime.service().chat("hello", false, true);
        assertNotNull(successRuntime.listener());
        successRuntime.listener().onResponse("reply ok");
        waitForEvents(successRuntime.ttsEvents(), 1);
        assertTrue(successRuntime.ttsEvents().getFirst().success());

        TestRuntime failureRuntime = createRuntime();
        writeConfigFiles(failureRuntime.modelConfigPath(), failureRuntime.ttsConfigPath(),
                "https://api.example.com", "sk-model", "gpt-4o",
                "https://tts.example.com", "sk-tts", "tts-model", "claire");

        failureRuntime.enqueueChatBot(mock(ChatBot.class));
        TtsClient failingClient = mock(TtsClient.class);
        doThrow(new IllegalStateException("async fail")).when(failingClient).speak("reply fail");
        failureRuntime.enqueueTtsClient(failingClient);
        failureRuntime.service().init();
        failureRuntime.service().chat("hello", false, true);
        assertNotNull(failureRuntime.listener());
        failureRuntime.listener().onResponse("reply fail");
        waitForEvents(failureRuntime.ttsEvents(), 1);
        assertFalse(failureRuntime.ttsEvents().getFirst().success());
        assertTrue(failureRuntime.ttsEvents().getFirst().error().contains("async fail"));
    }

    private TestRuntime createRuntime() {
        Path modelConfigPath = tempDir.resolve("openai_config.json");
        Path ttsConfigPath = tempDir.resolve("tts_config.json");
        TestableChatBotService service = new TestableChatBotService(
                objectMapper,
                new BotConfigStore(modelConfigPath, objectMapper),
                new TtsConfigStore(ttsConfigPath, objectMapper)
        );
        return new TestRuntime(service, modelConfigPath, ttsConfigPath);
    }

    private void writeConfigFiles(
            Path modelConfigPath,
            Path ttsConfigPath,
            String baseUrl,
            String modelApiKey,
            String modelName,
            String apiUri,
            String ttsApiKey,
            String ttsModel,
            String voice
    ) throws Exception {
        Files.writeString(modelConfigPath, """
                {
                  "BASE_URL_b": "%s",
                  "API_KEY_b": "%s",
                  "MODEL_NAME_b": "%s"
                }
                """.formatted(baseUrl, modelApiKey, modelName));

        Files.writeString(ttsConfigPath, """
                {
                  "apiUri": "%s",
                  "apiKey": "%s",
                  "model": "%s",
                  "voice": "%s"
                }
                """.formatted(apiUri, ttsApiKey, ttsModel, voice));
    }

    private void waitForEvents(List<TtsStatusEvent> events, int expectedSize) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (events.size() < expectedSize && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertTrue(events.size() >= expectedSize, "Expected tts-status event was not emitted");
    }

    private record TestRuntime(TestableChatBotService service, Path modelConfigPath, Path ttsConfigPath) {
        void enqueueChatBot(ChatBot chatBot) {
            service.enqueueChatBot(chatBot);
        }

        void enqueueTtsClient(TtsClient ttsClient) {
            service.enqueueTtsClient(ttsClient);
        }

        int chatBotCreateCount() {
            return service.chatBotCreateCount();
        }

        int ttsClientCreateCount() {
            return service.ttsClientCreateCount();
        }

        List<TtsStatusEvent> ttsEvents() {
            return service.ttsEvents();
        }

        BotResponseListener listener() {
            return service.listener();
        }
    }

    private static final class TestableChatBotService extends ChatBotService {

        private final Queue<ChatBot> chatBots = new ArrayDeque<>();
        private final Queue<TtsClient> ttsClients = new ArrayDeque<>();
        private final List<TtsStatusEvent> ttsEvents = new ArrayList<>();

        private BotResponseListener listener;
        private int chatBotCreateCount;
        private int ttsClientCreateCount;

        private TestableChatBotService(ObjectMapper objectMapper, BotConfigStore botConfigStore, TtsConfigStore ttsConfigStore) {
            super(objectMapper, botConfigStore, ttsConfigStore);
        }

        void enqueueChatBot(ChatBot chatBot) {
            chatBots.add(chatBot);
        }

        void enqueueTtsClient(TtsClient ttsClient) {
            ttsClients.add(ttsClient);
        }

        int chatBotCreateCount() {
            return chatBotCreateCount;
        }

        int ttsClientCreateCount() {
            return ttsClientCreateCount;
        }

        List<TtsStatusEvent> ttsEvents() {
            return ttsEvents;
        }

        BotResponseListener listener() {
            return listener;
        }

        @Override
        protected com.velinx.core.platform.config.ConfigManager createConfigManager() {
            return null;
        }

        @Override
        protected ChatBot createChatBot(com.velinx.core.platform.config.ConfigManager configManager, BotResponseListener listener) {
            this.listener = listener;
            this.chatBotCreateCount += 1;
            return chatBots.remove();
        }

        @Override
        protected TtsClient createTtsClient(com.velinx.core.platform.config.ConfigManager configManager) {
            this.ttsClientCreateCount += 1;
            return ttsClients.remove();
        }

        @Override
        protected void broadcast(String eventName, String data) {
        }

        @Override
        protected void broadcastJson(String eventName, Object data) {
            if ("tts-status".equals(eventName) && data instanceof TtsStatusEvent event) {
                ttsEvents.add(event);
            }
        }
    }
}
