package com.velinx.core.memory.embedding.db;

import com.velinx.core.platform.config.PathConfig;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EmbeddingDatabase {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingDatabase.class);

    public EmbeddingDatabase() {
    }

    public InMemoryEmbeddingStore<TextSegment> init(String name){
        Path vectorStorePath = Paths.get(PathConfig.DATA_DIR, name, "memory", "vector_store.json");
        // 如果本地存在之前保存的向量库数据，则直接加载，实现记忆持久化
        if (Files.exists(vectorStorePath)) {
            logger.info("已加载长期记忆向量库: {}", vectorStorePath);
            return InMemoryEmbeddingStore.fromFile(vectorStorePath);
        }
        return new InMemoryEmbeddingStore<>();
    }
}
