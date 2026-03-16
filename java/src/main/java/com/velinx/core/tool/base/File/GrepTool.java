package com.velinx.core.tool.base.File;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容搜索工具：按正则表达式搜索文件内容。
 * 类似 Claude Code 的 Grep tool。
 */
public class GrepTool {
    private final Path rootPath;
    private final Consumer<String> actionListener;
    private static final int MAX_MATCHES = 100;
    private static final int MAX_OUTPUT_CHARS = 30000;

    public GrepTool(String rootDir, Consumer<String> actionListener) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        this.actionListener = actionListener;
    }

    @Tool("按正则表达式搜索文件内容。返回匹配的文件名和行号。" +
          "支持 fileGlob 过滤文件类型，如 *.java 只搜索Java文件。" +
          "ignoreCase 为 true 时忽略大小写。")
    public String grep(
            @P("正则表达式模式，如 \"public class \\\\w+\" 或普通字符串") String pattern,
            @P("文件过滤 glob，如 *.java、*.xml，传空搜索所有文本文件") String fileGlob,
            @P("是否忽略大小写，默认 false") Boolean ignoreCase) {

        if (actionListener != null) {
            actionListener.accept("🔍 Grep搜索: " + pattern);
        }

        try {
            int flags = (ignoreCase != null && ignoreCase) ? Pattern.CASE_INSENSITIVE : 0;
            Pattern regex = Pattern.compile(pattern, flags);

            PathMatcher fileMatcher = (fileGlob != null && !fileGlob.isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)
                    : null;

            List<String> results = new ArrayList<>();
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("node_modules")
                            || name.equals("target") || name.equals(".idea")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_MATCHES) return FileVisitResult.TERMINATE;
                    if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE; // 跳过大文件
                    if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isTextFile(file)) return FileVisitResult.CONTINUE;

                    searchInFile(file, regex, results);
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) return "未找到匹配 \"" + pattern + "\" 的内容。";

            String header = "找到 " + results.size() + " 处匹配"
                    + (results.size() >= MAX_MATCHES ? "（已达上限）" : "") + ":\n\n";
            String output = header + String.join("\n", results);

            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS)
                        + "\n\n... [输出已截断]";
            }
            return output;

        } catch (Exception e) {
            return "Grep搜索失败: " + e.getMessage();
        }
    }

    private void searchInFile(Path file, Pattern regex, List<String> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            String relPath = rootPath.relativize(file).toString().replace("\\", "/");
            for (int i = 0; i < lines.size() && results.size() < MAX_MATCHES; i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    results.add(relPath + ":" + (i + 1) + " | " + lines.get(i).trim());
                }
            }
        } catch (Exception ignored) {
            // 跳过无法读取的文件（二进制等）
        }
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        // 常见文本文件扩展名
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")
                || name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".html")
                || name.endsWith(".css") || name.endsWith(".js") || name.endsWith(".ts")
                || name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".sql")
                || name.endsWith(".gradle") || name.endsWith(".cfg") || name.endsWith(".ini")
                || name.endsWith(".toml") || name.endsWith(".tsx") || name.endsWith(".jsx")
                || name.endsWith(".vue") || name.endsWith(".go") || name.endsWith(".rs")
                || name.endsWith(".kt") || name.endsWith(".scala") || name.endsWith(".c")
                || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".hpp")
                || !name.contains(".");  // 无扩展名的文件（如 Makefile、Dockerfile）
    }
}