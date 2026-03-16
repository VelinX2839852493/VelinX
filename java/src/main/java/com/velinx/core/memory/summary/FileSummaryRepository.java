package com.velinx.core.memory.summary;

import com.velinx.core.memory.SummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileSummaryRepository implements SummaryRepository {
    private static final Logger logger = LoggerFactory.getLogger(FileSummaryRepository.class);
    private final Path path;

    public FileSummaryRepository(Path path) {
        this.path = path;
    }

    @Override
    public String load() {
        try {
            if (Files.exists(path)) return Files.readString(path).trim();
        } catch (IOException e) {
            logger.error("加载总结文件失败: {}", path, e);
        }
        return "";
    }

    @Override
    public void save(String summary) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, summary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("保存总结文件失败: {}", path, e);
        }
    }
}