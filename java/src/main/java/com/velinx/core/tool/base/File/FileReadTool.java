package com.velinx.core.tool.base.File;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileReadTool {
    private final Path rootPath;
    private final Consumer<String> actionListener;
    /** 单次返回的最大字符数，防止 token 爆炸 */
    private static final int MAX_OUTPUT_CHARS = 30000;

    public FileReadTool(String rootDir, Consumer<String> actionListener) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        this.actionListener = actionListener;
    }

    @Tool("列出指定文件夹下的所有内容（包含子文件夹的树形结构）。想看整个项目传空字符串。")
    public String listDirectory(@P("相对路径，空字符串表示根目录") String subPath) {
        if (actionListener != null) {
            String displayPath = (subPath == null || subPath.trim().isEmpty()) ? "项目根目录" : subPath;
            actionListener.accept("🔍 正在扫描目录: " + displayPath);
        }
        try {
            String safePath = subPath == null ? "" : subPath.replaceAll("^[/\\\\]+", "");
            Path target = rootPath.resolve(safePath).normalize();
            if (!target.startsWith(rootPath)) return "错误：越界访问。";
            if (!Files.isDirectory(target)) return "错误：目标不是文件夹。";

            try (Stream<Path> stream = Files.walk(target, 8)) {
                String result = stream
                        .filter(p -> !p.equals(target))
                        .filter(p -> {
                            String s = p.toString();
                            return !s.contains(".git") && !s.contains("target")
                                    && !s.contains("node_modules");
                        })
                        .limit(200)
                        .map(p -> {
                            String rel = rootPath.relativize(p).toString().replace("\\", "/");
                            return (Files.isDirectory(p) ? "[DIR]  " : "[FILE] ") + rel;
                        })
                        .collect(Collectors.joining("\n"));
                return result.isEmpty() ? "（空文件夹）" : truncate(result);
            }
        } catch (Exception e) {
            return "目录读取失败: " + e.getMessage();
        }
    }

    @Tool("读取指定文件的内容，带行号输出。支持 offset（起始行，从1开始）和 limit（读取行数）进行分页读取。")
    public String readFile(
            @P("文件相对路径") String pathStr,
            @P("起始行号（从1开始），传 null 或 0 表示从头开始") Integer offset,
            @P("读取的行数，传 null 或 0 表示读到末尾") Integer limit) {

        if (actionListener != null) {
            actionListener.accept("📄 正在读取文件: " + pathStr);
        }

        try {
            String safePath = pathStr == null ? "" : pathStr.replaceAll("^[/\\\\]+", "");
            Path targetPath = rootPath.resolve(safePath).normalize();

            if (!targetPath.startsWith(rootPath)) return "错误：越界访问。";
            if (!Files.isRegularFile(targetPath)) return "错误：文件不存在 -> " + pathStr;

            List<String> allLines = Files.readAllLines(targetPath);
            int totalLines = allLines.size();

            int startLine = (offset != null && offset > 0) ? offset : 1;
            int maxLines = (limit != null && limit > 0) ? limit : totalLines;

            // 边界校正
            if (startLine > totalLines) {
                return "文件共 " + totalLines + " 行，offset=" + startLine + " 超出范围。";
            }

            int endLine = Math.min(startLine + maxLines - 1, totalLines);

            StringBuilder sb = new StringBuilder();
            for (int i = startLine - 1; i < endLine; i++) {
                sb.append(String.format("%4d | %s\n", i + 1, allLines.get(i)));
            }

            // 添加文件信息头
            String header = "[" + pathStr + "] 共 " + totalLines + " 行，显示第 "
                    + startLine + "-" + endLine + " 行\n";

            return truncate(header + sb);
        } catch (Exception e) {
            return "文件读取失败: " + e.getMessage();
        }
    }

    /** 截断过长输出，防止 token 爆炸 */
    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n... [输出已截断，共 " + text.length() + " 字符，显示前 " + MAX_OUTPUT_CHARS + " 字符]";
    }
}