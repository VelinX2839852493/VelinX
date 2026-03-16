package com.velinx.core.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 聊天记录存储接口
 */

public interface ShortChatHistoryStore {

    /**
     * （第一步）向历史记录中添加一条新的聊天消息。
     *
     * @param message 要添加的消息对象
     */
    void add(ChatMessage message);

    /**
     * 获取当前存储的所有聊天消息。
     *
     * @return 包含所有历史消息的列表
     */
    List<ChatMessage> getAllMessages();

    /**
     * 刷新聊天历史记录。
     * 该操作通常会清空当前存储并替换为提供的消息列表。
     *
     * @param messages 用于替换当前历史的新消息列表
     */
    void refresh(List<ChatMessage> messages);

    /**
     * 清除所有的聊天历史记录。
     */
    void clear();

    /**
     * 将整个对话历史转换为适用于 LLM（大语言模型）Prompt 的字符串格式。
     * 通常会将消息按角色（如 User, Assistant）和内容进行拼接。
     *
     * @return 格式化后的对话历史字符串
     */
    String getConversationHistoryAsPrompt();



    /**
     * 保存短期记忆
     */
    void saveShortTermMemory();
}




