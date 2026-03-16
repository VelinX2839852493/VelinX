package com.velinx.core.tool.base.weather;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class WeatherTool {
    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);
    private final Consumer<String> actionListener;

    public WeatherTool(Consumer<String> actionListener) {
        this.actionListener = actionListener;
    }
    @Tool("获取当前用户的真实环境（实时地理位置（城市）、当前天气状况、气温以及时间）")
    public String getCurrentEnvironmentContext() {
        actionListener.accept("AI 正在主动调用环境感知工具...");
        // 调用你之前的逻辑
        return SystemContextManager.getFullContext();
    }
}