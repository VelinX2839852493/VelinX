package com.velinx.core.memory;
/**
 * 总结内容的持久化仓库接口
 */
public interface SummaryRepository {
    /** 加载旧的总结内容 */
    String load();
    /** 保存新的总结内容 */
    void save(String summary);
}