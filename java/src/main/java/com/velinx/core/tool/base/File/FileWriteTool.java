package com.velinx.core.tool.base.File;

import com.velinx.core.tool.toolbox.BeasToolManager;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileWriteTool {
    private final Path rootPath;
    private final BeasToolManager.CodeReviewListener reviewListener;

    public FileWriteTool(String rootDir, BeasToolManager.CodeReviewListener listener) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        this.reviewListener = listener;
    }

    @Tool("将内容写入指定文件")
    public String writeFile(String pathStr, String content) {
        try {
            Path targetPath = rootPath.resolve(pathStr).normalize();
            if (!targetPath.startsWith(rootPath)) {
                return "错误：禁止写入工作区之外的路径。";
            }

            String oldCode = "";
            if (Files.exists(targetPath)) {
                oldCode = Files.readString(targetPath);
            }

            if (reviewListener != null) {
                boolean approved = reviewListener.onReview(pathStr, oldCode, content);
                if (!approved) {
                    return "写入已拒绝：用户未批准这次修改。";
                }
            }

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(targetPath, content);
            return "写入成功: " + pathStr;
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }
}
