package com.velinx.core.memory.embedding;

import com.velinx.core.memory.HippocampusManager;
import com.velinx.core.platform.config.PathConfig;
import com.velinx.core.memory.embedding.db.BgeEmbeddingConfig;
import com.velinx.core.memory.embedding.db.EmbeddingDatabase;
import com.velinx.core.memory.fact.FactCard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * 海马体 (Hippocampus) 模块
 * 负责长期记忆的存储与检索。实现 ContentRetriever 接口以接入 LangChain4j RAG 流程。
 * 特色功能：支持基于时间的记忆衰减（遗忘曲线），让较新的记忆更容易被检索到。
 */
public class Hippocampus implements ContentRetriever, HippocampusManager {
    private static final Logger logger = LoggerFactory.getLogger(Hippocampus.class);

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Path vectorStorePath;
    private final int maxResults;
    private final double minScore;
    private final double halfLifeDays; // 记忆半衰期（天）
    private final double lambda;       // 衰减常数

    /**
     * 便捷构造函数：根据角色名称初始化长期记忆
     * 自动配置向量数据库路径和 BGE 嵌入模型。
     *
     * @param name           用户或角色的唯一名称
     * @param maxResults     检索结果的最大数量
     * @param minScore       最低相似度分数阈值
     * @param halfLifeDays   记忆半衰期（天），决定了记忆“遗忘”的速度
     */
    public Hippocampus(String name,
                       int maxResults,
                       double minScore,
                       double halfLifeDays) {
        this(
                new EmbeddingDatabase().init(name),
                new BgeEmbeddingConfig().localBgeEmbeddingModel(),
                Paths.get(PathConfig.DATA_DIR, name, "memory", "vector_store.json"),
                maxResults,
                minScore,
                halfLifeDays
        );
    }

    /**
     * 全参数构造函数：用于内部初始化及设置衰减常数
     */
    public Hippocampus(InMemoryEmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       Path vectorStorePath,
                       int maxResults,
                       double minScore,
                       double halfLifeDays) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.vectorStorePath = vectorStorePath;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.halfLifeDays = halfLifeDays;
        // 计算公式：λ = ln(2) / t(1/2)
        this.lambda = Math.log(2) / halfLifeDays;
    }

    /**
     * 实现 ContentRetriever 接口的检索方法
     * 将检索到的匹配项转换为 Content 对象供 RAG 链条使用。
     *
     * @param query 包含检索文本的查询对象
     * @return 检索到的内容列表
     */
    @Override
    public List<Content> retrieve(Query query) {
        return searchFactMatches(query.text(), maxResults, minScore).stream()
                .map(sc -> Content.from(sc.match.embedded()))
                .toList();
    }

    /**
     * 检索相关事实的文本内容
     *
     * @param query 用户提问或搜索词
     * @return 匹配的事实文本列表（String形式）
     */
    public List<String> retrieveFacts(String query) {
        return searchFactCards(query, maxResults, minScore).stream()
                .map(FactCard::text)
                .toList();
    }

    /**
     * 搜索并返回结构化的事实卡片对象
     *
     * @param query          查询文本
     * @param limit          返回数量限制
     * @param scoreThreshold 最低分数阈值
     * @return 事实卡片对象列表
     */
    public List<FactCard> searchFactCards(String query, int limit, double scoreThreshold) {
        return searchFactMatches(query, limit, scoreThreshold).stream()
                .map(match -> FactCard.fromTextSegment(match.match.embedded()))
                .toList();
    }

    /**
     * 插入或更新事实卡片
     * 如果存在相同的 fact_key，则先删除旧的再插入新的，确保记忆库中不重复。
     *
     * @param facts 准备持久化的事实卡片列表
     */
    public void upsertFacts(List<FactCard> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }

        for (FactCard fact : facts) {
            // 根据元数据中的 fact_key 移除已存在的相同事实
            embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("fact_key").isEqualTo(fact.factKey()));
            Embedding embedding = embeddingModel.embed(fact.text()).content();
            embeddingStore.add(fact.factKey(), embedding, fact.toTextSegment());
        }
        save();
    }

    /**
     * 将当前的内存向量库序列化并保存到本地磁盘文件
     */
    public void save() {
        embeddingStore.serializeToFile(vectorStorePath);
    }

    /**
     * 核心检索逻辑：向量搜索 + 记忆衰减
     * 1. 执行初始向量检索。
     * 2. 对每个结果应用基于时间的衰减公式。
     * 3. 重新过滤并按照衰减后的分数排序。
     *
     * @param query          查询文本
     * @param limit          最终返回的数量
     * @param scoreThreshold 过滤的分数线
     * @return 带权重的匹配项列表
     */
    private List<ScoredContent> searchFactMatches(String query, int limit, double scoreThreshold) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        // 初始检索时多查一些（limit * 3），防止衰减后有效数据过少
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(limit, 1) * 3)
                .minScore(scoreThreshold)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        LocalDateTime now = LocalDateTime.now();

        return result.matches().stream()
                .map(match -> new ScoredContent(match, applyDecay(match, now))) // 应用衰减
                .filter(sc -> sc.decayedScore >= scoreThreshold) // 衰减后仍达标的结果
                .sorted(Comparator.comparingDouble(ScoredContent::decayedScore).reversed()) // 按衰减分重排
                .limit(limit)
                .peek(sc -> logger.debug("记忆检索: score={} -> decayed={}, text={}",
                        String.format("%.3f", sc.match.score()),
                        String.format("%.3f", sc.decayedScore),
                        sc.match.embedded().text().substring(0, Math.min(50, sc.match.embedded().text().length()))))
                .toList();
    }

    /**
     * 计算记忆衰减分数
     * 使用指数衰减公式：DecayedScore = OriginalScore * e^(-λ * days)
     * 这样可以模拟时间越久，记忆越模糊的特性。
     *
     * @param match 向量匹配项
     * @param now   当前时间
     * @return 衰减后的分数
     */
    private double applyDecay(EmbeddingMatch<TextSegment> match, LocalDateTime now) {
        try {
            String ts = match.embedded().metadata().getString("timestamp");
            if (ts != null) {
                LocalDateTime created = LocalDateTime.parse(ts);
                long days = ChronoUnit.DAYS.between(created, now);
                return match.score() * Math.exp(-lambda * days);
            }
        } catch (Exception ignored) {
            // 如果数据没有时间戳（例如旧数据），则保持原分，不进行衰减
        }
        return match.score();
    }

    /**
     * 内部辅助记录：存储匹配项及其衰减后的分数
     */
    private record ScoredContent(EmbeddingMatch<TextSegment> match, double decayedScore) {
    }

    /**
     * 获取底层的向量存储对象
     */
    public InMemoryEmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    /**
     * 获取底层的嵌入模型
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }
}
