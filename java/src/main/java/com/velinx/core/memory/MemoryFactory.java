package com.velinx.core.memory;

import com.velinx.core.memory.embedding.Hippocampus;
import com.velinx.core.memory.fact.FactCardExtractor;
import com.velinx.core.memory.retrieval.KnowledgeBaseManager;
import com.velinx.core.memory.store.ShortTermMemory;
import com.velinx.core.memory.summary.FileSummaryRepository;
import com.velinx.core.memory.summary.Summarizer;
import com.velinx.core.platform.config.ConfigManager;
import com.velinx.core.platform.config.PathConfig;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆工厂
 */
public class MemoryFactory {
    private static final Logger logger = LoggerFactory.getLogger(MemoryFactory.class);
    private static final String DOCS_DIR = "src/main/resources/docs";

    public static MemoryManager create(ProfileManager profile,
                                       String botName,
                                       ConfigManager config,
                                       ChatLanguageModel model,
                                       MemoryFeatures memoryFeatures) {
        // 1. 确定文件夹路径
        Path dataDir = Paths.get(PathConfig.DATA_DIR, botName,"memory");
        ensureDirectory(dataDir);

        // --- 准备 KnowledgeBaseManager 所需的零件 ---

        // 零件 A: 向量化模型 (把文字转成数字)
        EmbeddingModel embeddingModel = new BgeSmallZhQuantizedEmbeddingModel();

        // 零件 B: 向量数据库 (存储这些数字)
        Path vectorStorePath = dataDir.resolve("my_vectors.json");
        EmbeddingStoreLoadResult embeddingStoreLoadResult = loadEmbeddingStore(vectorStorePath);

        // --- 正式开始组装 ---

        // 2. 准备具体的实现类
        ShortChatHistoryStore stm = new ShortTermMemory(dataDir.resolve("short_term_memory.json"));
        HippocampusManager ltm = new Hippocampus(botName, 3, 0.45, 30);

        // 【这里就是你要的答案】把上面准备好的零件塞进 KnowledgeBaseManager
        KnowledgeBaseManager kbm = new KnowledgeBaseManager(embeddingModel, embeddingStoreLoadResult.embeddingStore());
        // 顺便让它同步一下本地文档
        if (embeddingStoreLoadResult.forceReindex()) {
            logger.warn("知识库向量文件不可用，正在重建: {}", vectorStorePath);
            kbm.importDocuments(DOCS_DIR, true);
            kbm.saveToFile(vectorStorePath);
        } else {
            kbm.syncDocuments(DOCS_DIR, vectorStorePath);
        }

        // 3. 组装总结器仓库
        SummaryRepository sumRepo = new FileSummaryRepository(dataDir.resolve("summary.txt"));
        Summarizer summarizer = new Summarizer(sumRepo);

        // 4. 准备后台工人（线程池）
        // 名字改得专业点，方便调试
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Memory-Worker-" + botName);
            t.setDaemon(true); // 设置为守护线程，程序关了它自动关
            return t;
        });

        // 5. 【拼装！】
        return new MemoryManager(
                memoryFeatures,
                stm, summarizer, ltm, kbm,
                new FactCardExtractor(config),
                profile, model, config, executor
        );
    }

    private static void ensureDirectory(Path dataDir) {
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建记忆目录: " + dataDir, e);
        }
    }

    private static EmbeddingStoreLoadResult loadEmbeddingStore(Path vectorStorePath) {
        if (!java.nio.file.Files.exists(vectorStorePath)) {
            return new EmbeddingStoreLoadResult(new InMemoryEmbeddingStore<>(), false);
        }

        try {
            if (java.nio.file.Files.size(vectorStorePath) == 0L) {
                logger.warn("检测到空的知识库向量文件，将回退到新向量库: {}", vectorStorePath);
                return new EmbeddingStoreLoadResult(new InMemoryEmbeddingStore<>(), true);
            }

            EmbeddingStore<TextSegment> embeddingStore = InMemoryEmbeddingStore.fromFile(vectorStorePath);
            if (embeddingStore == null) {
                logger.warn("知识库向量文件加载结果为空，将重建: {}", vectorStorePath);
                return new EmbeddingStoreLoadResult(new InMemoryEmbeddingStore<>(), true);
            }

            return new EmbeddingStoreLoadResult(embeddingStore, false);
        } catch (Exception e) {
            logger.warn("知识库向量文件加载失败，将重建: {}", vectorStorePath, e);
            return new EmbeddingStoreLoadResult(new InMemoryEmbeddingStore<>(), true);
        }
    }

    private record EmbeddingStoreLoadResult(
            EmbeddingStore<TextSegment> embeddingStore,
            boolean forceReindex
    ) {
    }
}
