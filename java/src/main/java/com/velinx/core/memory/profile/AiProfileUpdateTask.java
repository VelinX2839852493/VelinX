package com.velinx.core.memory.profile;

/**
 * 封装更新 AI 档案的任务数据和模板逻辑
 */
public record AiProfileUpdateTask(
        String systemInstruction,  // 来自 config 的基础指令
        String currentProfile,     // 当前旧档案内容
        String chatHistory         // 格式化后的对话历史
) {
    /**
     * 生成最终交给大模型的完整 Prompt 字符串
     */
    public String buildPrompt() {
        return """
            %s
            你需要根据一段最新的对话记录，以及AI当前的原始档案，提炼出新的信息，并整合出一份【更新后的AI档案】。
            
            【AI当前档案】：
            %s
            
            【最新对话记录】：
            %s
            
            【任务要求】：
            1. 分析对话记录中AI展现出的新性格、新状态、或者新获取的关键设定信息。
            2. 将新信息融入到当前档案中，剔除矛盾或过时的内容。
            3. 保持Markdown格式的条理性。
            4. 极其重要：请直接输出更新后的档案文本！不要包含任何开头语！不要有任何多余的废话！
            """.formatted(systemInstruction, currentProfile, chatHistory);
    }
}