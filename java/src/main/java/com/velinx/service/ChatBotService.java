package com.velinx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.core.TTS.ApiTtsClient;
import com.velinx.core.TTS.TtsClient;
import com.velinx.core.chat.runtime.BotResponseListener;
import com.velinx.core.chat.runtime.ChatBot;
import com.velinx.core.chat.runtime.ChatTranscriptLogger;
import com.velinx.core.platform.config.BotConfigStore;
import com.velinx.core.platform.config.BotWorkspaceResolver;
import com.velinx.core.platform.config.ConfigManager;
import com.velinx.core.platform.config.PathConfig;
import com.velinx.core.platform.config.TtsConfigStore;
import com.velinx.core.platform.review.BotReviewCoordinator;
import com.velinx.dto.DebugConfigResponse;
import com.velinx.dto.DebugStatusResponse;
import com.velinx.dto.ModelConfigPayload;
import com.velinx.dto.ModelConfigSummary;
import com.velinx.dto.TtsConfigPayload;
import com.velinx.dto.TtsConfigSummary;
import com.velinx.dto.TtsStatusEvent;
import com.velinx.dto.TtsTestResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ChatBotService {

    private static final Logger logger = LoggerFactory.getLogger(ChatBotService.class);
    private static final String DEFAULT_TTS_SMOKE_TEXT = "VelinX backend TTS smoke test.";
    private static final String TTS_SOURCE_CHAT = "bot-tts";
    private static final String TTS_SOURCE_DEBUG = "debug-tts";

    private final ObjectMapper jsonMapper;
    private final BotReviewCoordinator reviewCoordinator;
    private final BotConfigStore configStore;
    private final TtsConfigStore ttsConfigStore;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private volatile ChatBot chatBot;
    private volatile TtsClient ttsClient;
    private volatile boolean ttsOpen;
    private volatile boolean initialized;

    @Value("${WorkBot.name:text}")
    private String botName;

    @Value("${WorkBot.role:1}")
    private String botRole;

    @Value("${WorkBot.workspaceDir:C:/Users/28398/Desktop/text}")
    private String configuredWorkspaceDir;

    @Autowired
    public ChatBotService(ObjectMapper jsonMapper) {
        this(
                jsonMapper,
                new BotConfigStore(Path.of(PathConfig.OPENAI_CONFIG_PATH), jsonMapper),
                new TtsConfigStore(Path.of(PathConfig.TTS_CONFIG_PATH), jsonMapper)
        );
    }

    ChatBotService(ObjectMapper jsonMapper, BotConfigStore configStore, TtsConfigStore ttsConfigStore) {
        this.jsonMapper = jsonMapper;
        this.reviewCoordinator = new BotReviewCoordinator(jsonMapper);
        this.configStore = configStore;
        this.ttsConfigStore = ttsConfigStore;
    }

    @PostConstruct
    public void init() {
        rebuildRuntime();
    }

    public synchronized DebugStatusResponse getDebugStatus() {
        ModelConfigPayload modelConfig = readModelConfig();
        TtsConfigPayload ttsConfig = readTtsConfig();
        boolean botReady = chatBot != null;
        boolean ttsReady = ttsClient != null;

        return new DebugStatusResponse(
                botReady && ttsReady,
                initialized,
                botReady,
                ttsReady,
                summarizeModel(modelConfig, botReady),
                summarizeTts(ttsConfig, ttsReady)
        );
    }

    public synchronized DebugConfigResponse getDebugConfig() {
        return new DebugConfigResponse(readModelConfig(), readTtsConfig());
    }

    public synchronized void rebuildBot(String baseUrl, String apiKey, String modelName) throws Exception {
        configStore.updateModelConfig(normalizeNullable(baseUrl), normalizeNullable(apiKey), normalizeNullable(modelName));
        rebuildRuntime();
        broadcast("message", "Model config updated, bot runtime rebuilt.");
    }

    public synchronized void rebuildTtsClient(String apiUri, String apiKey, String model, String voice) throws Exception {
        ttsConfigStore.updateConfig(
                normalizeNullable(apiUri),
                normalizeNullable(apiKey),
                normalizeNullable(model),
                normalizeNullable(voice)
        );

        ConfigManager configManager = createConfigManager();
        ttsClient = createTtsClient(configManager);
        initialized = chatBot != null || ttsClient != null;
        broadcast("message", "TTS config updated, client rebuilt.");
    }

    public synchronized TtsTestResponse testTts(String text) {
        String normalizedText = normalizeTestText(text);
        TtsClient activeTtsClient = ttsClient;
        if (activeTtsClient == null) {
            TtsTestResponse response = new TtsTestResponse(
                    false,
                    0L,
                    normalizedText,
                    "TTS client is not initialized. Please check /developer/settings/tts.",
                    TTS_SOURCE_DEBUG
            );
            emitTtsStatus(response);
            return response;
        }

        long startedAt = System.nanoTime();
        try {
            activeTtsClient.speak(normalizedText);
            TtsTestResponse response = new TtsTestResponse(
                    true,
                    elapsedMillis(startedAt),
                    normalizedText,
                    null,
                    TTS_SOURCE_DEBUG
            );
            emitTtsStatus(response);
            return response;
        } catch (Exception e) {
            logger.warn("TTS smoke test failed: {}", e.getMessage(), e);
            TtsTestResponse response = new TtsTestResponse(
                    false,
                    elapsedMillis(startedAt),
                    normalizedText,
                    e.getMessage(),
                    TTS_SOURCE_DEBUG
            );
            emitTtsStatus(response);
            return response;
        }
    }

    public void chat(String userMessage) {
        chat(userMessage, false, false);
    }

    public void chat(String userMessage, boolean captureDesktop, boolean tts) {
        logger.info("User message received, forwarding to ChatBot. captureDesktop={}, tts={}", captureDesktop, tts);
        ChatTranscriptLogger.user(userMessage, captureDesktop);
        ttsOpen = tts;

        ChatBot activeChatBot = chatBot;
        if (activeChatBot == null) {
            broadcast("error", "Bot runtime is not initialized. Please verify model configuration.");
            return;
        }

        activeChatBot.sendMessage(userMessage, captureDesktop);
    }

    public void resolveReview(boolean isApproved) {
        reviewCoordinator.resolveReview(isApproved);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(exception -> emitters.remove(emitter));
        return emitter;
    }

    @PreDestroy
    public void destroy() {
        stopChatBot();
    }

    protected ConfigManager createConfigManager() {
        return new ConfigManager(botName, botRole);
    }

    protected ChatBot createChatBot(ConfigManager configManager, BotResponseListener listener) {
        return new ChatBot(
                botName,
                botRole,
                PathConfig.SKILLS_DIR,
                BotWorkspaceResolver.resolve(resolveWorkspaceDir()),
                configManager,
                listener
        );
    }

    protected TtsClient createTtsClient(ConfigManager configManager) {
        String apiUri = requireConfigValue(configManager.get_tts("apiUri"), "TTS apiUri");
        String apiKey = requireConfigValue(configManager.get_tts("apiKey"), "TTS apiKey");
        String model = requireConfigValue(configManager.get_tts("model"), "TTS model");
        String voice = requireConfigValue(configManager.get_tts("voice"), "TTS voice");

        return new ApiTtsClient(
                HttpClient.newHttpClient(),
                URI.create(apiUri),
                apiKey,
                model,
                voice
        );
    }

    protected void broadcast(String eventName, String data) {
        broadcast(eventName, data, MediaType.TEXT_PLAIN);
    }

    protected void broadcastJson(String eventName, Object data) {
        try {
            broadcast(eventName, jsonMapper.writeValueAsString(data), MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            logger.warn("Failed to serialize SSE payload for event {}", eventName, e);
        }
    }

    private synchronized void rebuildRuntime() {
        stopChatBot();
        chatBot = null;
        ttsClient = null;
        ttsOpen = false;
        initialized = false;

        ConfigManager configManager;
        try {
            configManager = createConfigManager();
        } catch (Exception e) {
            logger.error("Failed to create config manager", e);
            return;
        }

        try {
            chatBot = createChatBot(configManager, createSharedListener());
        } catch (Exception e) {
            logger.error("Failed to initialize bot runtime", e);
        }

        try {
            ttsClient = createTtsClient(configManager);
        } catch (Exception e) {
            logger.error("Failed to initialize TTS runtime", e);
        }

        initialized = chatBot != null || ttsClient != null;
    }

    private void stopChatBot() {
        ChatBot activeChatBot = chatBot;
        if (activeChatBot != null) {
            activeChatBot.stop();
        }
    }

    private BotResponseListener createSharedListener() {
        return new BotResponseListener() {
            @Override
            public void onResponse(String text) {
                ChatTranscriptLogger.ai(text);
                if (ttsOpen) {
                    speakAsync(text);
                }
                broadcast("message", text);
            }

            @Override
            public void onStreamToken(String token) {
                broadcast("stream", token);
            }

            @Override
            public void onError(String msg) {
                logger.error("AI processing failed: {}", msg);
                ChatTranscriptLogger.system("Error: " + msg);
                broadcast("error", msg);
            }

            @Override
            public void onAction(String actionMsg) {
                logger.info("Local action: {}", actionMsg);
                broadcast("action", actionMsg);
            }

            @Override
            public void onTaskUpdate(String taskMsg) {
                logger.info("Task update: {}", taskMsg);
                broadcast("task", taskMsg);
            }

            @Override
            public void onTokenUpdate(String tokenMsg) {
                broadcast("token", tokenMsg);
            }

            @Override
            public boolean onCodeReview(String fileName, String oldCode, String newCode) {
                logger.info("[Service] review requested, waiting for confirmation");
                return reviewCoordinator.requestReview(
                        fileName,
                        oldCode,
                        newCode,
                        payload -> broadcast("review", payload),
                        exception -> logger.error("Review flow failed, change rejected by default", exception)
                );
            }

            @Override
            public void onWorkflowComplete() {
                logger.info("[Service] current workflow completed");
                broadcast("message", "Current workflow completed.");
            }
        };
    }

    private void speakAsync(String text) {
        TtsClient activeTtsClient = ttsClient;
        if (activeTtsClient == null) {
            emitTtsStatus(new TtsTestResponse(
                    false,
                    0L,
                    summarizeText(text),
                    "TTS client is not initialized. Please check /developer/settings/tts.",
                    TTS_SOURCE_CHAT
            ));
            return;
        }

        CompletableFuture.runAsync(() -> {
            long startedAt = System.nanoTime();
            try {
                activeTtsClient.speak(text);
                emitTtsStatus(new TtsTestResponse(
                        true,
                        elapsedMillis(startedAt),
                        summarizeText(text),
                        null,
                        TTS_SOURCE_CHAT
                ));
            } catch (Exception e) {
                logger.warn("TTS failed: {}", e.getMessage(), e);
                emitTtsStatus(new TtsTestResponse(
                        false,
                        elapsedMillis(startedAt),
                        summarizeText(text),
                        e.getMessage(),
                        TTS_SOURCE_CHAT
                ));
            }
        });
    }

    private void emitTtsStatus(TtsTestResponse response) {
        broadcastJson("tts-status", new TtsStatusEvent(
                response.success(),
                response.durationMs(),
                response.text(),
                response.error(),
                response.source()
        ));
    }

    private void broadcast(String eventName, String data, MediaType mediaType) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, mediaType));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    private ModelConfigPayload readModelConfig() {
        try {
            return configStore.readModelConfig();
        } catch (Exception e) {
            logger.warn("Failed to read model config", e);
            return new ModelConfigPayload("", "", "");
        }
    }

    private TtsConfigPayload readTtsConfig() {
        try {
            return ttsConfigStore.readTtsConfig();
        } catch (Exception e) {
            logger.warn("Failed to read TTS config", e);
            return new TtsConfigPayload("", "", "", "");
        }
    }

    private ModelConfigSummary summarizeModel(ModelConfigPayload config, boolean ready) {
        boolean hasApiKey = hasText(config.apiKey());
        return new ModelConfigSummary(
                hasText(config.baseUrl()) && hasText(config.modelName()) && hasApiKey,
                ready,
                config.baseUrl(),
                config.modelName(),
                hasApiKey,
                maskSecret(config.apiKey())
        );
    }

    private TtsConfigSummary summarizeTts(TtsConfigPayload config, boolean ready) {
        boolean hasApiKey = hasText(config.apiKey());
        return new TtsConfigSummary(
                hasText(config.apiUri()) && hasText(config.model()) && hasText(config.voice()) && hasApiKey,
                ready,
                config.apiUri(),
                config.model(),
                config.voice(),
                hasApiKey,
                maskSecret(config.apiKey())
        );
    }

    private String resolveWorkspaceDir() {
        if (!hasText(configuredWorkspaceDir)) {
            return PathConfig.ROOT_DIR;
        }
        return configuredWorkspaceDir;
    }

    private String requireConfigValue(String value, String label) {
        String normalized = normalizeKnownPlaceholder(value);
        if (!hasText(normalized)) {
            throw new IllegalStateException(label + " is missing");
        }
        return normalized;
    }

    private String normalizeKnownPlaceholder(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || "None".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeTestText(String text) {
        String normalized = normalizeNullable(text);
        return normalized == null ? DEFAULT_TTS_SMOKE_TEXT : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String maskSecret(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private String summarizeText(String text) {
        if (!hasText(text)) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
