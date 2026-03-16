package com.velinx.core.memory;

/**
 * 身份档案接口
 */
public interface ProfileManager {

    /**
     * 根据对话历史动态更新 AI 的角色设定或状态。
     * 该方法会分析历史文本，从中提取关键信息来调整 AI 的性格表现、情感状态或当前认知。
     *
     * @param history 用于分析的对话历史字符串
     */
    void updateAiProfileByHistory(String history);

    /**
     * 获取当前 AI 的角色设定描述。
     * 返回的内容通常作为 System Prompt 的一部分，定义 AI 的身份、语气和行为准则。
     *
     * @return AI 的角色设定字符串
     */
    String getAiProfile();

    /**
     * 获取当前用户的画像信息。
     * 包含从历史交互中总结出的用户偏好、背景习惯或基本信息，用于辅助生成个性化回复。
     *
     * @return 用户画像描述字符串
     */
    String getUserProfile();
}