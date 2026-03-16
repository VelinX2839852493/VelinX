package com.velinx.core.tool.toolbox;

import com.velinx.core.tool.ToolBoxConfig;
import com.velinx.core.tool.base.File.*;
import com.velinx.core.tool.base.weather.WeatherTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 工具管理器：负责创建和管理所有工具实例。
 * 工具直接注册到 LangChain4j，不再需要 dispatch 路由。
 */
public class BeasToolManager implements ToolBoxConfig {
    private final List<Object> enabledTools = new ArrayList<>();

    public interface CodeReviewListener {
        boolean onReview(String fileName, String oldCode, String newCode);
    }

    private final String baseDir;

    boolean enableWeather = false;
    boolean enableRead = false;
    boolean enableWrite = false;
    boolean enableEdit = false;
    boolean enableTerm = false;
    boolean enableGlob = false;
    boolean enableGrep = false;

    Consumer<String> actionListener;
    CodeReviewListener reviewListener;

    public BeasToolManager(
            String baseDir,
            Consumer<String> actionListener,
            CodeReviewListener reviewListener
    ) {
        this.baseDir = baseDir;
        this.actionListener = actionListener;
        this.reviewListener = reviewListener;
    }

    public void init() {
        if (enableRead) enabledTools.add(new FileReadTool(baseDir, actionListener));
        if (enableWrite) enabledTools.add(new FileWriteTool(baseDir, reviewListener));
        if (enableEdit) enabledTools.add(new FileEditTool(baseDir, actionListener, reviewListener));
        if (enableTerm) enabledTools.add(new TerminalTool(baseDir, actionListener, reviewListener));
        if (enableGlob) enabledTools.add(new GlobTool(baseDir, actionListener));
        if (enableGrep) enabledTools.add(new GrepTool(baseDir, actionListener));
        if (enableWeather) enabledTools.add(new WeatherTool(actionListener));
    }

    public void setEnableRead(boolean v) { this.enableRead = v; }
    public void setEnableWrite(boolean v) { this.enableWrite = v; }
    public void setEnableEdit(boolean v) { this.enableEdit = v; }
    public void setEnableTerm(boolean v) { this.enableTerm = v; }
    public void setEnableGlob(boolean v) { this.enableGlob = v; }
    public void setEnableGrep(boolean v) { this.enableGrep = v; }
    public void setEnableWeather(boolean v) { this.enableWeather = v; }

    public boolean isEnableRead() { return enableRead; }
    public boolean isEnableWrite() { return enableWrite; }
    public boolean isEnableEdit() { return enableEdit; }
    public boolean isEnableTerm() { return enableTerm; }
    public boolean isEnableGlob() { return enableGlob; }
    public boolean isEnableGrep() { return enableGrep; }
    public boolean isEnableWeather() { return enableWeather; }

    /** 获取所有已启用的工具实例，直接注册到 LangChain4j */
    public List<Object> getTools() {
        return enabledTools;
    }
}
