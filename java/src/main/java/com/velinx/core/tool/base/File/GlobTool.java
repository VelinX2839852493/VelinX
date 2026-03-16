package com.velinx.core.tool.base.File;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 文件模式匹配工具：按 glob 模式搜索文件。
 * 类似 Claude Code 的 Glob tool。
 */
public class GlobTool {
    private final Path rootPath;
    private final Consumer<String> actionListener;
    private static final int MAX_RESULTS = 200;

    public GlobTool(String rootDir, Consumer<String> actionListener) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        this.actionListener = actionListener;
    }

    @Tool("按文件名模式搜索文件。支持 glob 语法，如 **/*.java 匹配所有Java文件，src/**/*.xml 匹配src下所有XML文件。")
    public String glob(
            @P("glob 模式，如 **/*.java、src/**/*.ts、**/pom.xml") String pattern,
            @P("搜索的子目录（相对路径），传空字符串表示从项目根目录搜索") String subPath) {

        if (actionListener != null) {
            actionListener.accept("🔎 Glob搜索: " + pattern);
        }

        try {
            String safeSub = (subPath == null || subPath.isBlank()) ? "" : subPath.replaceAll("^[/\\\\]+", "");
            Path searchRoot = rootPath.resolve(safeSub).normalize();
            if (!searchRoot.startsWith(rootPath)) return "错误：越界访问。";
            if (!Files.isDirectory(searchRoot)) return "错误：目录不存在 -> " + safeSub;

            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern);

            List<String> matches = new ArrayList<>();
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("node_modules") || name.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = rootPath.relativize(file);
                    if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                        matches.add(relative.toString().replace("\\", "/"));
                    }
                    if (matches.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) return "未找到匹配 \"" + pattern + "\" 的文件。";

            String header = "找到 " + matches.size() + " 个匹配文件"
                    + (matches.size() >= MAX_RESULTS ? "（已达上限 " + MAX_RESULTS + "）" : "")
                    + ":\n";
            return header + String.join("\n", matches);

        } catch (Exception e) {
            return "Glob搜索失败: " + e.getMessage();
        }
    }
}