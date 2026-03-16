package com.velinx.core.memory;


import com.velinx.core.memory.fact.FactCard;

import java.util.List;

/**
 * 长期记忆接口
 */
public interface HippocampusManager {

    /**
     * 根据查询语句检索相关的事实内容。
     * 通常用于简单的文本提取，直接返回与查询最相关的知识片段。
     *
     * @param query 查询字符串（如用户的提问或关键词）
     * @return 匹配到的事实描述列表
     */
    List<String> retrieveFacts(String query);

    /**
     * 更新或插入事实卡片（Upsert 操作）。
     * 如果事实卡片已存在则进行更新，如果不存在则存入新的事实。
     *
     * @param facts 要持久化保存的事实卡片（FactCard）列表
     */
    void upsertFacts(List<FactCard> facts);

    /**
     * 对事实卡片进行高级搜索。
     * 支持指定返回数量上限以及基于相关性评分的过滤阈值。
     *
     * @param text     搜索文本或向量化的索引词
     * @param max      最大返回记录数
     * @param minScore 最小评分阈值（通常为相似度得分，范围 0.0 到 1.0），低于此分数的结果将被过滤
     * @return 符合评分要求的 {@link FactCard} 列表
     */
    List<FactCard> searchFactCards(String text, int max, double minScore);
}
