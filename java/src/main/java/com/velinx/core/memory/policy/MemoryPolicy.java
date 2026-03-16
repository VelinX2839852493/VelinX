package com.velinx.core.memory.policy;

import com.velinx.core.memory.fact.FactCard;
import com.velinx.core.memory.fact.FactType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆库正则匹配策略
 */
public final class MemoryPolicy {
    private static final List<String> LONG_TERM_MEMORY_CUES = List.of(
            "记得", "还记得", "上次", "之前", "我喜欢", "我不喜欢", "别", "不要", "我叫", "我是", "你说过"
    );
    private static final List<String> KNOWLEDGE_RETRIEVAL_CUES = List.of(
            "文档", "docs", "知识库", "说明", "指南", "原理", "路线", "面试题", "项目学习", "根据文档", "参考资料"
    );
    private static final Set<FactType> PROFILE_REFRESH_TYPES = EnumSet.of(
            FactType.RELATIONSHIP,
            FactType.AI_PROFILE,
            FactType.CONSTRAINT
    );

    private MemoryPolicy() {
    }

    public static boolean shouldRetrieveLongTermMemory(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }

        String normalized = userInput.trim().toLowerCase();
        return LONG_TERM_MEMORY_CUES.stream().anyMatch(normalized::contains);
    }

    public static boolean shouldRetrieveKnowledge(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }

        String normalized = userInput.trim().toLowerCase();
        return KNOWLEDGE_RETRIEVAL_CUES.stream().anyMatch(normalized::contains);
    }

    public static boolean shouldRefreshAiProfile(List<FactCard> acceptedFacts,
                                                 boolean summaryTriggered,
                                                 int turnsSinceLastProfileRefresh,
                                                 int fallbackTurns) {
        return shouldRefreshAiProfile(
                acceptedFacts,
                summaryTriggered,
                turnsSinceLastProfileRefresh,
                fallbackTurns,
                1
        );
    }

    public static boolean shouldRefreshAiProfile(List<FactCard> acceptedFacts,
                                                 boolean summaryTriggered,
                                                 int turnsSinceLastProfileRefresh,
                                                 int fallbackTurns,
                                                 int completedTurnsInBatch) {
        if (summaryTriggered) {
            return true;
        }

        if (acceptedFacts != null && acceptedFacts.stream()
                .map(FactCard::factType)
                .anyMatch(PROFILE_REFRESH_TYPES::contains)) {
            return true;
        }

        return turnsSinceLastProfileRefresh + Math.max(completedTurnsInBatch, 1) >= fallbackTurns;
    }
}
