package com.velinx.core.tool.base.File;

import com.velinx.core.tool.toolbox.BeasToolManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * 精确编辑工具，按字符串匹配替换指定片段。
 */
public class FileEditTool {
    private final Path rootPath;
    private final BeasToolManager.CodeReviewListener reviewListener;
    private final Consumer<String> actionListener;

    public FileEditTool(String rootDir,
                        Consumer<String> actionListener,
                        BeasToolManager.CodeReviewListener reviewListener) {
        this.rootPath = Paths.get(rootDir).toAbsolutePath().normalize();
        this.actionListener = actionListener;
        this.reviewListener = reviewListener;
    }

    @Tool("精确编辑文件：用 newString 替换文件中的 oldString。只需要提供要改的片段，不需要输出整个文件。")
    public String editFile(
            @P("文件相对路径") String path,
            @P("要被替换的原始文本，必须与文件内容完全一致") String oldString,
            @P("替换后的新文本") String newString,
            @P("是否替换所有匹配项，默认 false") Boolean replaceAll) {

        if (actionListener != null) {
            actionListener.accept("正在编辑文件: " + path);
        }

        try {
            Path targetPath = rootPath.resolve(path).normalize();
            if (!targetPath.startsWith(rootPath)) {
                return "错误：禁止编辑工作区之外的文件。";
            }
            if (!Files.isRegularFile(targetPath)) {
                return "错误：文件不存在 -> " + path;
            }

            String content = Files.readString(targetPath);

            if (!content.contains(oldString)) {
                return "错误：未找到匹配片段，请确认 oldString 与文件内容完全一致。";
            }

            boolean doReplaceAll = replaceAll != null && replaceAll;
            if (!doReplaceAll) {
                int first = content.indexOf(oldString);
                int second = content.indexOf(oldString, first + 1);
                if (second != -1) {
                    return "错误：oldString 在文件中出现多次，请补充上下文或设置 replaceAll=true。";
                }
            }

            String newContent = doReplaceAll
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(
                    java.util.regex.Pattern.quote(oldString),
                    java.util.regex.Matcher.quoteReplacement(newString));

            if (reviewListener != null) {
                boolean approved = reviewListener.onReview(path, content, newContent);
                if (!approved) {
                    return "编辑已拒绝：用户未批准这次修改。";
                }
            }

            Files.writeString(targetPath, newContent);
            int count = doReplaceAll ? countOccurrences(content, oldString) : 1;
            return "编辑成功: " + path + " (替换 " + count + " 处)";

        } catch (Exception e) {
            return "编辑失败: " + e.getMessage();
        }
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
