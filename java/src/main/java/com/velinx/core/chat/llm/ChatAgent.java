package com.velinx.core.chat.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * agent接口
 */
public interface ChatAgent {
    @SystemMessage({
            "=== AI 角色设定 (AI Profile) ===",
            "{{aiProfile}}",
            "",
            "=== 用户档案 (User Profile) ===",
            "{{userProfile}}",
            "",
            "=== 通用系统指令 (General Prompt) ===",
            "{{generalPrompt}}"
    })
    String chat(
            @UserMessage String userMessage,
            @V("aiProfile") String aiProfile,
            @V("userProfile") String userProfile,
            @V("generalPrompt") String generalPrompt
    );
}
