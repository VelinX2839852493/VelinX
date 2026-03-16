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
import com.velinx.core.platform.review.BotReviewCoordinator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final BotReviewCoordinator reviewCoordinator = new BotReviewCoordinator(jsonMapper);
    private final BotConfigStore configStore = new BotConfigStore(Path.of(PathConfig.OPENAI_CONFIG_PATH), jsonMapper);
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private ChatBot chatBot;
    private boolean ttsOpen;


    @Value("${WorkBot.name:text}")
    private String botName;

    @Value("${WorkBot.role:1}")
    private String botRole;

    private TtsClient ttsClient ;


    @PostConstruct
    public void init() {
        String configuredWorkspaceDir = "C:/Users/28398/Desktop/text";
        ConfigManager configManager = new ConfigManager(botName, botRole);
        this.ttsOpen = false;
        chatBot = new ChatBot(
                botName,
                botRole,
                PathConfig.SKILLS_DIR,
                BotWorkspaceResolver.resolve(configuredWorkspaceDir),
                configManager,
                createSharedListener()
        );



        this.ttsClient = new ApiTtsClient(
                HttpClient.newHttpClient(),
                URI.create(configManager.get_tts("apiUri")),
                configManager.get_tts("apiKey"),
                configManager.get_tts("model"),
                configManager.get_tts("voice")
        );

    }

    //回调函数
    private BotResponseListener createSharedListener() {
        return new BotResponseListener() {
            @Override
            public void onResponse(String text) {
                ChatTranscriptLogger.ai(text);
                if(ttsOpen){
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

    public void chat(String userMessage) {
        chat(userMessage, false,false);
    }

    public void chat(String userMessage, boolean captureDesktop,boolean tts) {
        logger.info("用户Received, forwarding to ChatBot. captureDesktop={}", captureDesktop);
        ChatTranscriptLogger.user(userMessage, captureDesktop);

        ttsOpen = tts;
        if (chatBot != null) {
            chatBot.sendMessage(userMessage, captureDesktop);
        }
    }

    public void resolveReview(boolean isApproved) {
        reviewCoordinator.resolveReview(isApproved);
    }

    public void rebuildBot(String baseUrl, String apiKey, String modelName) {
        if (chatBot != null) {
            chatBot.stop();
        }

        try {
            configStore.updateModelConfig(baseUrl, apiKey, modelName);
        } catch (Exception e) {
            logger.error("Failed to update config", e);
        }

        init();
        broadcast("message", "Config updated, bot rebuilt.");
    }

    private void broadcast(String eventName, String data) {

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    private void speakAsync(String text) {
        CompletableFuture.runAsync(() -> {
            try {
                ttsClient.speak(text);
            } catch (Exception e) {
                logger.warn("TTS failed: {}", e.getMessage(), e);
            }
        });
    }

    @PreDestroy
    public void destroy() {
        if (chatBot != null) {
            chatBot.stop();
        }
    }
}
