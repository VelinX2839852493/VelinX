package com.velinx.core.chat.runtime;

import com.velinx.core.chat.llm.ChatAgent;
import com.velinx.core.memory.MemoryManager;
import com.velinx.core.tool.ToolBoxConfig;
import com.velinx.core.tool.ToolManager;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBotWorkTest {

    @Test
    void executeFlowShouldSendSuccessfulResponse() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        when(chatAgent.chat(anyString(), anyString(), anyString(), anyString())).thenReturn("Hello back");
        BotResponseListener listener = mock(BotResponseListener.class);

        ChatBotWork work = new ChatBotWork(
                chatAgent,
                new NoOpToolBoxConfig(),
                listener,
                () -> "",
                mock(ChatLanguageModel.class),
                mock(MemoryManager.class),
                new NoOpToolManager()
        );

        ChatBotWork.TurnExecutionResult result = work.executeFlow("hello", "ai", "user", "prompt");

        assertTrue(result.success());
        assertEquals("Hello back", result.finalText());
        verify(listener).onResponse("Hello back");
        verify(listener, never()).onError(anyString());
    }

    @Test
    void executeFlowShouldTreatBlankResponseAsFailure() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        when(chatAgent.chat(anyString(), anyString(), anyString(), anyString())).thenReturn("   ");
        BotResponseListener listener = mock(BotResponseListener.class);

        ChatBotWork work = new ChatBotWork(
                chatAgent,
                new NoOpToolBoxConfig(),
                listener,
                () -> "",
                mock(ChatLanguageModel.class),
                mock(MemoryManager.class),
                new NoOpToolManager()
        );

        ChatBotWork.TurnExecutionResult result = work.executeFlow("hello", "ai", "user", "prompt");

        assertFalse(result.success());
        assertEquals("执行失败: 模型返回空白回复。", result.errorMessage());
        verify(listener).onError("执行失败: 模型返回空白回复。");
        verify(listener, never()).onResponse(anyString());
    }

    private static final class NoOpToolBoxConfig implements ToolBoxConfig {

        @Override
        public void init() {
        }

        @Override
        public void setEnableRead(boolean v) {
        }

        @Override
        public void setEnableWrite(boolean v) {
        }

        @Override
        public void setEnableEdit(boolean v) {
        }

        @Override
        public void setEnableTerm(boolean v) {
        }

        @Override
        public void setEnableGlob(boolean v) {
        }

        @Override
        public void setEnableGrep(boolean v) {
        }

        @Override
        public void setEnableWeather(boolean v) {
        }

        @Override
        public List<Object> getTools() {
            return List.of();
        }
    }

    private static final class NoOpToolManager implements ToolManager {

        @Override
        public String executeCommand(String command, Integer timeoutSeconds) {
            return "";
        }

        @Override
        public String openToolbox() {
            return "";
        }

        @Override
        public String toolboxAction(String toolName, String paramsJson) {
            return "";
        }

        @Override
        public String getToolboxPrompt() {
            return "";
        }
    }
}
