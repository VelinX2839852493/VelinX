package com.velinx.core.memory;

import java.util.List;



/**
 * 知识库接口
 */
public interface KnowledgeBase {

    /**
     * 根据查询语句检索相关的知识片段。
     * 系统会通过语义匹配或关键词搜索，从知识库中提取出最有参考价值的信息。
     *
     * @param query 查询字符串（通常是用户的原始提问或提取后的关键词）
     * @return 包含相关背景知识的字符串列表；若未找到匹配内容，则返回空列表
     */
    List<String> retrieveRelevantKnowledge(String query);
}