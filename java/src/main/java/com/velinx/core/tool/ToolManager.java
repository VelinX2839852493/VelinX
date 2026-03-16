package com.velinx.core.tool;

public interface ToolManager {

    /**
     * 直接执行终端命令。
     *
     * @param command 命令内容
     * @param timeoutSeconds 超时时间（秒）
     * @return 执行结果
     */
    String executeCommand(String command, Integer timeoutSeconds);

    /**
     * 打开工具箱。
     *
     * @return 工具箱说明
     */
    String openToolbox();

    /**
     * 执行工具箱中的具体工具。
     *
     * @param toolName 工具名称
     * @param paramsJson JSON 参数
     * @return 执行结果
     */
    String toolboxAction(String toolName, String paramsJson);

    /**
     * 工具箱的提示词。
     *
     * @return 提示词内容
     */
    String getToolboxPrompt();
}
