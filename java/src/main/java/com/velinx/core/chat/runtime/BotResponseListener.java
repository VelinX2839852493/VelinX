package com.velinx.core.chat.runtime;

/**
 * 回调函数接口
 */
public interface BotResponseListener {
    void onResponse(String text);
    void onStreamToken(String token);
    void onError(String msg);
    void onAction(String actionMsg);
    void onTaskUpdate(String taskMsg);
    void onTokenUpdate(String tokenMsg);

    // 阻塞审核回调
    boolean onCodeReview(String fileName, String oldCode, String newCode);

    // 🌟 新增：工作流彻底结束的回调（用于交还控制权）
    void onWorkflowComplete();
}
