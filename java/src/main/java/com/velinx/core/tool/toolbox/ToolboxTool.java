package com.velinx.core.tool.toolbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velinx.core.tool.ToolBoxConfig;
import com.velinx.core.tool.ToolManager;
import com.velinx.core.tool.base.File.FileEditTool;
import com.velinx.core.tool.base.File.FileReadTool;
import com.velinx.core.tool.base.File.FileWriteTool;
import com.velinx.core.tool.base.File.GlobTool;
import com.velinx.core.tool.base.File.GrepTool;
import com.velinx.core.tool.base.File.TerminalTool;
import com.velinx.core.tool.base.weather.WeatherTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具箱：对模型暴露文件与搜索相关能力。
 * 终端能力单独直接暴露，不再通过工具箱二次转发。
 */
public class ToolboxTool implements ToolManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Consumer<String> actionCallback;
    private final Map<String, String> toolDescriptions = new LinkedHashMap<>();

    private FileReadTool fileReadTool;
    private FileWriteTool fileWriteTool;
    private FileEditTool fileEditTool;
    private TerminalTool terminalTool;
    private GlobTool globTool;
    private GrepTool grepTool;
    private WeatherTool weatherTool;

    public ToolboxTool(ToolBoxConfig manager, Consumer<String> actionCallback) {
        this.actionCallback = actionCallback;
        registerTools(manager);
    }

    private void registerTools(ToolBoxConfig manager) {
        for (Object tool : manager.getTools()) {
            if (tool instanceof FileReadTool t) {
                fileReadTool = t;
                toolDescriptions.put("readFile", "读取文件并按行查看。参数: {\"path\":\"文件路径\", \"offset\":起始行, \"limit\":行数}");
            } else if (tool instanceof FileWriteTool t) {
                fileWriteTool = t;
                toolDescriptions.put("writeFile", "创建或覆盖文件。参数: {\"path\":\"路径\", \"content\":\"内容\"}");
            } else if (tool instanceof FileEditTool t) {
                fileEditTool = t;
                toolDescriptions.put("editFile", "精确替换文件中的字符串。参数: {\"path\":\"路径\", \"oldString\":\"原文本\", \"newString\":\"新文本\", \"replaceAll\":false}");
            } else if (tool instanceof TerminalTool t) {
                terminalTool = t;
            } else if (tool instanceof GlobTool t) {
                globTool = t;
                toolDescriptions.put("glob", "按模式搜索文件名。参数: {\"pattern\":\"**/*.java\", \"subPath\":\"\"}");
            } else if (tool instanceof GrepTool t) {
                grepTool = t;
                toolDescriptions.put("grep", "按正则搜索文件内容。参数: {\"pattern\":\"正则\", \"fileGlob\":\"*.java\", \"ignoreCase\":false}");
            } else if (tool instanceof WeatherTool t) {
                weatherTool = t;
                toolDescriptions.put("getEnvironment", "获取环境上下文，如位置、天气、时间。参数: {}");
            }
        }
    }

    @Override
    @Tool("直接在本地终端执行命令。")
    public String executeCommand(
            @P("要执行的命令") String command,
            @P("超时时间（秒），传 null 使用默认值") Integer timeoutSeconds) {
        if (terminalTool == null) {
            return "错误: 终端工具未启用";
        }
        return terminalTool.executeCommand(command, timeoutSeconds);
    }

    @Override
    @Tool("打开工具箱，查看全部文件与搜索相关工具及参数格式。需要读取文件、修改文件、搜索代码时，优先调用这个方法。")
    public String openToolbox() {
        if (actionCallback != null) {
            actionCallback.accept("已打开工具箱");
        }

        StringBuilder sb = new StringBuilder("=== 工具箱 ===\n");
        sb.append("调用方式: toolboxAction(toolName, paramsJson)\n\n");
        for (var entry : toolDescriptions.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @Override
    @Tool("执行工具箱中的具体工具。必须先调用 openToolbox() 查看可用工具和参数格式。")
    public String toolboxAction(
            @P("工具名称，如 readFile、editFile、glob、grep、writeFile 等") String toolName,
            @P("JSON 格式参数，具体格式见 openToolbox() 返回说明") String paramsJson) {

        if (toolName == null || toolName.isBlank()) {
            return "错误: toolName 不能为空，请先调用 openToolbox()";
        }
        if (actionCallback != null) {
            actionCallback.accept("调用工具: " + toolName);
        }

        try {
            JsonNode p = MAPPER.readTree((paramsJson != null && !paramsJson.isBlank()) ? paramsJson : "");

            return switch (toolName) {
                case "listDirectory" -> {
                    if (fileReadTool == null) yield "错误: 文件读取工具未启用";
                    yield fileReadTool.listDirectory(str(p, "subPath", ""));
                }
                case "readFile" -> {
                    if (fileReadTool == null) yield "错误: 文件读取工具未启用";
                    yield fileReadTool.readFile(str(p, "path", ""), intVal(p, "offset"), intVal(p, "limit"));
                }
                case "writeFile" -> {
                    if (fileWriteTool == null) yield "错误: 文件写入工具未启用";
                    yield fileWriteTool.writeFile(str(p, "path", ""), str(p, "content", ""));
                }
                case "editFile" -> {
                    if (fileEditTool == null) yield "错误: 文件编辑工具未启用";
                    yield fileEditTool.editFile(
                            str(p, "path", ""),
                            str(p, "oldString", ""),
                            str(p, "newString", ""),
                            boolVal(p, "replaceAll")
                    );
                }
                case "glob" -> {
                    if (globTool == null) yield "错误: Glob 工具未启用";
                    yield globTool.glob(str(p, "pattern", ""), str(p, "subPath", ""));
                }
                case "grep" -> {
                    if (grepTool == null) yield "错误: Grep 工具未启用";
                    yield grepTool.grep(str(p, "pattern", ""), str(p, "fileGlob", ""), boolVal(p, "ignoreCase"));
                }
                case "getEnvironment" -> {
                    if (weatherTool == null) yield "错误: 环境感知工具未启用";
                    yield weatherTool.getCurrentEnvironmentContext();
                }
                default -> "未知工具: " + toolName + "，请先调用 openToolbox() 查看可用工具";
            };
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }

    @Override
    public String getToolboxPrompt() {
        return """
                【工具箱】你拥有一个工具箱，里面是文件和搜索相关工具。
                在需要读取文件、修改文件、搜索代码时，优先先调用 openToolbox() 查看工具说明。
                终端命令请直接调用 executeCommand，不要通过工具箱转发。
                """;
    }

    private String str(JsonNode node, String field, String def) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : def;
    }

    private Integer intVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isNumber()) ? v.asInt() : null;
    }

    private Boolean boolVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isBoolean()) ? v.asBoolean() : null;
    }

    public String getToolSummary() {
        StringBuilder sb = new StringBuilder();
        for (var entry : toolDescriptions.entrySet()) {
            String desc = entry.getValue();
            int stop = desc.indexOf('。');
            if (stop > 0) {
                desc = desc.substring(0, stop);
            }
            sb.append("- ").append(entry.getKey()).append(": ").append(desc).append("\n");
        }
        return sb.toString();
    }
}
