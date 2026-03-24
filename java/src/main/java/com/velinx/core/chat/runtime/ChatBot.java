package com.velinx.core.chat.runtime;

import com.velinx.core.chat.llm.ChatAgent;
import com.velinx.core.memory.MemoryFactory;
import com.velinx.core.memory.MemoryFeatures;
import com.velinx.core.memory.MemoryManager;
import com.velinx.core.memory.ProfileManager;
import com.velinx.core.memory.profile.Profile;
import com.velinx.core.skill.SkillFactory;
import com.velinx.core.skill.SkillManager;
import com.velinx.core.platform.config.ConfigManager;
import com.velinx.core.platform.config.ModelConfigNormalizer;
import com.velinx.core.platform.observability.ChatBotTokenTracker;
import com.velinx.core.tool.ToolBoxConfig;
import com.velinx.core.tool.ToolManager;
import com.velinx.core.tool.toolbox.BeasToolManager;
import com.velinx.core.tool.toolbox.ToolboxTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class ChatBot {

    private static final long WORKER_STOP_WAIT_MS = 5000L;

    private record ChatRequest(String message, boolean captureDesktop, boolean stopSignal) {
        private static ChatRequest text(String message) {
            return new ChatRequest(message, false, false);
        }

        private static ChatRequest withDesktop(String message) {
            return new ChatRequest(message, true, false);
        }

        private static ChatRequest stop() {
            return new ChatRequest("", false, true);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ChatBot.class);

    private final String name;
    private final String role;
    private final String skillsDir;
    private final String workspaceDir;
    private final BlockingQueue<ChatRequest> inputQueue = new LinkedBlockingQueue<>();
    private final BotResponseListener listener;
    private final Thread workerThread;

    private ProfileManager profile;
    private ChatLanguageModel chatModel;
    private ChatLanguageModel fallbackModel;
    private MemoryManager memoryManager;
    private SkillManager skillManager;
    private MemoryFeatures memoryFeatures;

    private String aiProfile = "";
    private String userProfile = "";

    private ConfigManager config;
    private ToolManager toolbox;
    private ChatBotWork chatBotWork;
    private ToolBoxConfig toolsManager;

    private volatile boolean isRunning = true;

    public ChatBot(String name,
                   String role,
                   String skillsDir,
                   String workspaceDir,
                   ConfigManager config,
                   BotResponseListener listener
    ) {
        this.name = name;
        this.role = role;
        this.skillsDir = skillsDir;
        this.workspaceDir = workspaceDir;
        this.listener = listener;

        this.config = config;

        init();

        this.workerThread = new Thread(this::workLoop, "ChatBot-Worker-" + name);
        this.workerThread.start();
    }

    private void init() {
        logger.info("Initializing ChatBot");

        memoryFeatures = new MemoryFeatures();
        memoryFeatures.setEnableProfileUpdate(false);

        ChatBotTokenTracker tokenTracker = new ChatBotTokenTracker(0.0035, 0.007, listener);
        initApi(tokenTracker);

        profile = new Profile(name, fallbackModel, config);
        refreshProfiles();

        memoryManager = MemoryFactory.create(profile, name, config, fallbackModel, memoryFeatures);
        Consumer<String> actionCallback = actionMessage -> {
            if (listener != null) {
                listener.onAction(actionMessage);
            }
        };
        skillManager = SkillFactory.create(skillsDir, workspaceDir, actionCallback);
        toolbox = initTools(actionCallback);

        ChatAgent chatAgent = AiServices.builder(ChatAgent.class)
                .chatLanguageModel(chatModel)
                .chatMemory(memoryManager)
                .tools(toolbox)
                .build();

        this.chatBotWork = new ChatBotWork(
                chatAgent,
                toolsManager,
                listener,
                () -> {
                    try {
                        return inputQueue.take().message();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "q";
                    }
                },
                chatModel,
                memoryManager,
                toolbox
        );
    }

    private void workLoop() {
        logger.info("ChatBot worker started");

        while (isRunning) {
            try {
                ChatRequest request = inputQueue.take();
                if (request.stopSignal()) {
                    isRunning = false;
                    break;
                }

                String userInput = request.message();
                refreshProfiles();
                memoryManager.prepareTurn(userInput);
                skillManager.prepareTurn(userInput);

                String generalPrompt = config.get_general_prompt()
                        + "\nWorkspace: " + workspaceDir
                        + "\n\n"
                        + skillManager.getActiveSkillPrompt()
                        + "\n\n"
                        + toolbox.getToolboxPrompt();

                ChatBotWork.TurnExecutionResult turnResult = request.captureDesktop()
                        ? chatBotWork.executeVisionFlow(userInput, aiProfile, userProfile, generalPrompt)
                        : chatBotWork.executeFlow(userInput, aiProfile, userProfile, generalPrompt);

                if (turnResult.success()) {
                    memoryManager.commitTurn();
                    memoryManager.afterTurn(userInput, turnResult.finalText(), fallbackModel);
                } else {
                    memoryManager.abortTurn();
                }

            } catch (InterruptedException e) {
                logger.warn("ChatBot worker interrupted");
                memoryManager.abortTurn();
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Chat flow failed", e);
                memoryManager.abortTurn();
                if (listener != null) {
                    listener.onError("执行失败: " + e.getMessage());
                }
            }
        }

        logger.info("ChatBot worker exited");
    }

    private void initApi(ChatBotTokenTracker tokenTracker) {
        String normalizedBaseUrl = ModelConfigNormalizer.normalizeBaseUrl(config.get_openai("BASE_URL_b"));

        chatModel = OpenAiChatModel.builder()
                .apiKey(config.get_openai("API_KEY_b"))
                .baseUrl(normalizedBaseUrl)
                .modelName(config.get_openai("MODEL_NAME_b"))
                .temperature(0.5)
                .maxTokens(4096)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(120))
                .listeners(java.util.List.of(tokenTracker))
                .build();

        fallbackModel = OpenAiChatModel.builder()
                .apiKey(config.get_openai("API_KEY_b"))
                .baseUrl(normalizedBaseUrl)
                .modelName(config.get_openai("MODEL_NAME_b"))
                .temperature(0.5)
                .maxTokens(4096)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(120))
                .listeners(java.util.List.of(tokenTracker))
                .build();
    }

    private void refreshProfiles() {
        this.aiProfile = profile.getAiProfile();
        this.userProfile = profile.getUserProfile();
    }

    private ToolboxTool initTools(Consumer<String> actionCallback) {
        this.toolsManager = new BeasToolManager(
                workspaceDir,
                actionCallback,
                (fileName, oldCode, newCode) -> listener != null && listener.onCodeReview(fileName, oldCode, newCode)
        );

        toolsManager.setEnableRead(true);
        toolsManager.setEnableWrite(true);
        toolsManager.setEnableEdit(true);
        toolsManager.setEnableTerm(true);
        toolsManager.setEnableGlob(true);
        toolsManager.setEnableGrep(true);
        toolsManager.setEnableWeather(true);
        toolsManager.init();

        return new ToolboxTool(toolsManager, actionCallback);
    }

    public void sendMessage(String message) {
        sendMessage(message, false);
    }

    public void sendMessage(String message, boolean captureDesktop) {
        inputQueue.offer(captureDesktop ? ChatRequest.withDesktop(message) : ChatRequest.text(message));
    }

    public void stop() {
        this.isRunning = false;
        this.inputQueue.offer(ChatRequest.stop());
        this.workerThread.interrupt();

        if (Thread.currentThread() == this.workerThread) {
            return;
        }

        try {
            this.workerThread.join(WORKER_STOP_WAIT_MS);
            if (this.workerThread.isAlive()) {
                logger.warn("ChatBot worker did not exit within {} ms", WORKER_STOP_WAIT_MS);
            } else {
                logger.info("ChatBot worker stopped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for ChatBot worker to stop");
        }
    }
}
