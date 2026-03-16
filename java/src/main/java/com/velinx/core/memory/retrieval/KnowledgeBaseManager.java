package com.velinx.core.memory.retrieval;

import com.velinx.core.memory.KnowledgeBase;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库管理器 (RAG)
 * 职责：实现了 KnowledgeBase 接口，负责本地文档的向量化入库和相关知识的检索
 */
// 2. 这里必须加上 implements KnowledgeBase
public class KnowledgeBaseManager implements KnowledgeBase {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseManager.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ContentRetriever contentRetriever;

    public KnowledgeBaseManager(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        if (embeddingStore == null) {
            logger.warn("EmbeddingStore is null, falling back to an empty in-memory store.");
            this.embeddingStore = new InMemoryEmbeddingStore<>();
        } else {
            this.embeddingStore = embeddingStore;
        }

        this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(this.embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.75)
                .build();
    }

    /**
     * 【核心实现】根据用户问题，检索最相关的知识片段
     * 这里加上 @Override，代表这是对接口“合同”的履行
     */
    @Override
    public List<String> retrieveRelevantKnowledge(String query) {
        Query q = Query.from(query);
        List<Content> contents = contentRetriever.retrieve(q);

        return contents.stream()
                .map(content -> content.textSegment().text())
                .collect(Collectors.toList());
    }

    // ============================================================
    // 以下是知识库的维护逻辑（导入、同步、保存），不属于接口定义的“检索”职责，
    // 但作为具体实现类，这些方法依然保留供工厂类或管理类使用。
    // ============================================================

    public void importDocuments(String directoryPath, boolean forceReindex) {
        if (!forceReindex) {
            logger.info("跳过知识库增量更新，直接使用已有向量数据。");
            return;
        }

        try {
            Path path = Paths.get(directoryPath);
            if (!path.toFile().exists()) {
                logger.warn("知识库目录不存在: {}", directoryPath);
                return;
            }

            logger.info("正在从 {} 加载文档并进行向量化...", directoryPath);

            List<Document> documents = FileSystemDocumentLoader.loadDocuments(path);
            DocumentByParagraphSplitter paragraphSplitter = new DocumentByParagraphSplitter(1000, 200);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(paragraphSplitter)
                    .textSegmentTransformer(textSegment -> {
                        String fileName = textSegment.metadata().getString("file_name");
                        String content = (fileName != null ? "[" + fileName + "]\n" : "") + textSegment.text();
                        return TextSegment.from(content, textSegment.metadata());
                    })
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();

            ingestor.ingest(documents);
            logger.info("知识库入库完成，共加载 {} 个文档。", documents.size());

        } catch (Exception e) {
            logger.error("知识库导入失败: ", e);
        }
    }

    public void syncDocuments(String directoryPath, Path vectorStorePath) {
        Path docsPath = Paths.get(directoryPath);
        if (!shouldReindex(docsPath, vectorStorePath)) {
            logger.info("Knowledge base index is up to date: {}", vectorStorePath);
            return;
        }

        if (!(embeddingStore instanceof InMemoryEmbeddingStore<?>)) {
            logger.warn("当前 EmbeddingStore 类型不支持原地重建");
            return;
        }

        @SuppressWarnings("unchecked")
        InMemoryEmbeddingStore<TextSegment> inMemoryStore = (InMemoryEmbeddingStore<TextSegment>) embeddingStore;
        inMemoryStore.removeAll();
        importDocuments(directoryPath, true);
        saveToFile(vectorStorePath);
    }

    boolean shouldReindex(Path docsPath, Path vectorStorePath) {
        if (java.nio.file.Files.notExists(docsPath) || !java.nio.file.Files.isDirectory(docsPath)) {
            logger.warn("知识库目录不存在: {}", docsPath);
            return false;
        }

        if (java.nio.file.Files.notExists(vectorStorePath)) {
            return true;
        }

        try {
            if (java.nio.file.Files.size(vectorStorePath) == 0L) {
                logger.warn("知识库向量文件为空，准备重建: {}", vectorStorePath);
                return true;
            }

            FileTime vectorModifiedTime = java.nio.file.Files.getLastModifiedTime(vectorStorePath);
            try (Stream<Path> stream = java.nio.file.Files.walk(docsPath)) {
                return stream
                        .filter(java.nio.file.Files::isRegularFile)
                        .anyMatch(path -> isNewerThan(path, vectorModifiedTime));
            }
        } catch (IOException e) {
            logger.warn("读取知识库时间戳失败，强制重建索引", e);
            return true;
        }
    }

    private boolean isNewerThan(Path path, FileTime baseline) {
        try {
            return java.nio.file.Files.getLastModifiedTime(path).compareTo(baseline) > 0;
        } catch (IOException e) {
            logger.warn("读取文档时间戳失败，强制重建索引: {}", path, e);
            return true;
        }
    }

    public void saveToFile(Path path) {
        if (embeddingStore instanceof InMemoryEmbeddingStore) {
            ((InMemoryEmbeddingStore<TextSegment>) embeddingStore).serializeToFile(path);
            logger.info("✅ 向量库已成功保存至文件: {}", path);
        } else {
            logger.warn("当前 EmbeddingStore 类型不支持本地序列化");
        }
    }

    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }
}
