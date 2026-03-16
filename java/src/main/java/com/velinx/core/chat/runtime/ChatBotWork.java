package com.velinx.core.chat.runtime;

import com.velinx.core.chat.llm.ChatAgent;
import com.velinx.core.memory.MemoryManager;
import com.velinx.core.tool.ToolBoxConfig;
import com.velinx.core.tool.ToolManager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatBotWork {

    private static final Logger logger = LoggerFactory.getLogger(ChatBotWork.class);
    private static final int MAX_SEQUENTIAL_TOOL_EXECUTIONS = 100;

    public interface UserInputProvider {
        String takeInput() throws InterruptedException;
    }

    private final ChatAgent chatAgent;
    private final ToolBoxConfig toolsManager;
    private final BotResponseListener listener;
    private final UserInputProvider inputProvider;
    private final ChatLanguageModel chatModel;
    private final MemoryManager memoryManager;
    private final DesktopCaptureService desktopCaptureService;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, ToolExecutor> toolExecutors;

    public ChatBotWork(ChatAgent chatAgent,
                       ToolBoxConfig toolsManager,
                       BotResponseListener listener,
                       UserInputProvider inputProvider,
                       ChatLanguageModel chatModel,
                       MemoryManager memoryManager,
                       ToolManager toolbox) {
        this.chatAgent = chatAgent;
        this.toolsManager = toolsManager;
        this.listener = listener;
        this.inputProvider = inputProvider;
        this.chatModel = chatModel;
        this.memoryManager = memoryManager;
        this.desktopCaptureService = new DesktopCaptureService();
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(toolbox);
        this.toolExecutors = buildToolExecutors(toolbox);
    }

    public String executeFlow(String userInput, String aiProfile, String userProfile, String generalPrompt) {
        try {
            String response = chatAgent.chat(userInput, aiProfile, userProfile, generalPrompt);
            sendResponse(response);
            return response;
        } catch (Exception e) {
            logger.error("[SYSTEM] Chat failed", e);
            sendError("执行失败: " + e.getMessage());
            return "";
        }
    }

    public String executeVisionFlow(String userInput, String aiProfile, String userProfile, String generalPrompt) {
        try {
            sendActionMsg("正在截取桌面截图");
            DesktopScreenshot screenshot = desktopCaptureService.captureDesktop();
            sendActionMsg("已附带桌面截图: " + screenshot.width() + "x" + screenshot.height());

            UserMessage runtimeUserMessage = UserMessage.from(
                    TextContent.from(userInput),
                    ImageContent.from(
                            screenshot.base64Data(),
                            screenshot.mimeType(),
                            ImageContent.DetailLevel.HIGH
                    )
            );

            String response = executeManualFlow(
                    SystemMessage.from(buildSystemPrompt(aiProfile, userProfile, generalPrompt)),
                    UserMessage.from(userInput),
                    runtimeUserMessage
            );
            sendResponse(response);
            return response;
        } catch (Exception e) {
            logger.error("[SYSTEM] Vision chat failed", e);
            sendError(buildVisionErrorMessage(e));
            return "";
        }
    }

    public ToolBoxConfig getFileTools() {
        return this.toolsManager;
    }

    public UserInputProvider getInputProvider() {
        return inputProvider;
    }

    private Map<String, ToolExecutor> buildToolExecutors(Object toolbox) {
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        for (Method method : toolbox.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }

            String toolName = annotation.name().isBlank() ? method.getName() : annotation.name();
            executors.put(toolName, new DefaultToolExecutor(toolbox, method));
        }
        return executors;
    }

    private String executeManualFlow(SystemMessage systemMessage,
                                     UserMessage historyUserMessage,
                                     UserMessage runtimeUserMessage) {
        memoryManager.add(systemMessage);
        memoryManager.add(historyUserMessage);

        List<ChatMessage> messages = new ArrayList<>(memoryManager.messages());
        replaceLastMessage(messages, runtimeUserMessage);
        Response<AiMessage> response = generate(messages);
        int executionsLeft = MAX_SEQUENTIAL_TOOL_EXECUTIONS;

        while (true) {
            if (executionsLeft-- == 0) {
                throw new IllegalStateException("Exceeded sequential tool execution limit");
            }

            AiMessage aiMessage = response.content();
            memoryManager.add(aiMessage);
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() == null ? "" : aiMessage.text();
            }

            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                ToolExecutor executor = toolExecutors.get(toolRequest.name());
                if (executor == null) {
                    throw new IllegalStateException("Tool executor not found: " + toolRequest.name());
                }

                String displayName = resolveToolDisplayName(toolRequest.name());
                sendActionMsg("正在执行工具: " + displayName);
                String toolResult = executor.execute(toolRequest, memoryManager.id());
                ToolExecutionResultMessage toolResultMessage = ToolExecutionResultMessage.from(toolRequest, toolResult);
                memoryManager.add(toolResultMessage);
                messages.add(toolResultMessage);
            }

            response = generate(messages);
        }
    }

    private Response<AiMessage> generate(List<ChatMessage> messages) {
        if (toolSpecifications.isEmpty()) {
            return chatModel.generate(messages);
        }
        return chatModel.generate(messages, toolSpecifications);
    }

    private void replaceLastMessage(List<ChatMessage> messages, ChatMessage replacement) {
        if (messages.isEmpty()) {
            throw new IllegalStateException("No message available for replacement");
        }
        messages.set(messages.size() - 1, replacement);
    }

    private String buildSystemPrompt(String aiProfile, String userProfile, String generalPrompt) {
        return String.join(
                "\n",
                "=== AI Profile ===",
                aiProfile,
                "",
                "=== User Profile ===",
                userProfile,
                "",
                "=== General Prompt ===",
                generalPrompt
        );
    }

    private String resolveToolDisplayName(String toolName) {
        return switch (toolName) {
            case "openToolbox" -> "打开工具箱";
            case "toolboxAction" -> "执行工具箱操作";
            default -> toolName;
        };
    }

    private void sendActionMsg(String msg) {
        if (listener != null) {
            listener.onAction(msg);
        }
    }

    private void sendResponse(String text) {
        if (listener != null) {
            listener.onResponse(text);
        }
    }

    private void sendError(String text) {
        if (listener != null) {
            listener.onError(text);
        }
    }

    private String buildVisionErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "执行失败: 当前视觉请求未成功完成。";
        }

        String normalized = message.toLowerCase();
        boolean unsupportedMultimodal = normalized.contains("can only concatenate str")
                || normalized.contains("error rendering prompt template")
                || normalized.contains("list\") to str")
                || normalized.contains("engine internal server error");

        if (unsupportedMultimodal) {
            return "执行失败: 当前配置的模型或接口暂不支持图片多模态输入。"
                    + " 你现在使用的这条 OpenAI 兼容链路已经收到了桌面截图，但服务端没有正确处理 text + image 消息。"
                    + " 请切换到明确支持视觉输入的模型或接口，例如支持 VL 或 vision 的模型，然后再试。";
        }

        return "执行失败: " + message;
    }
}
