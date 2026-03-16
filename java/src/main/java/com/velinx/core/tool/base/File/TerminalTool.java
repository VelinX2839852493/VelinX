package com.velinx.core.tool.base.File;

import com.velinx.core.tool.toolbox.BeasToolManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TerminalTool {

    private final BeasToolManager.CodeReviewListener reviewListener;
    private final Consumer<String> actionListener;
    private final File workDir;
    private static final int MAX_OUTPUT_CHARS = 30000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    public TerminalTool(String baseDir,
                        Consumer<String> actionListener,
                        BeasToolManager.CodeReviewListener reviewListener) {
        this.reviewListener = reviewListener;
        this.actionListener = actionListener;
        this.workDir = new File(baseDir);
    }

    /**
     * 核心修改：明确告知 AI 这是一个 Bash 环境
     */
    @Tool("在本地终端执行命令。支持标准的 Linux 命令。" +
            "【重要】探索项目时，请优先使用 'ls -R' 或 'find . -maxdepth 3' 来一次性获取多层目录结构，" +
            "不要一层一层地查看，以提高效率。")
    public String executeCommand(
            @P("要执行的 Bash 命令") String command,
            @P("超时时间（秒），传 null 使用默认120秒") Integer timeoutSeconds) {

        if (actionListener != null) {
            actionListener.accept("⚡ 执行命令: " + command);
        }
        // --- 修改点：判断是否需要审核 ---
        if (reviewListener != null) {
            // 如果不是“安全”的只读命令，才触发审核
            if (isDangerousCommand(command)) {
                boolean approved = reviewListener.onReview("[Terminal Warning]", "检测到敏感操作，请确认", command);
                if (!approved) {
                    return "执行被拒绝：用户未批准该敏感终端命令。";
                }
            }
        }
        // ------------------------------

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;

        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            String[] cmd;
            if (isWindows) {
                // 修改点 1: 寻找 Git Bash 路径
                // 如果你已经把 Git 加入了环境变量 PATH，可以直接写 "bash.exe"
                // 否则建议写全路径，通常在 "C:\\Program Files\\Git\\bin\\bash.exe"
                String bashPath = getGitBashPath();
                cmd = new String[]{bashPath, "-c", command};
            } else {
                cmd = new String[]{"sh", "-c", command};
            }

            Process process = new ProcessBuilder(cmd)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();

            // 修改点 2: 编码统一使用 UTF-8
            // Git Bash (MinGW) 在 Windows 上默认输出是 UTF-8，而 CMD 才是 GBK
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            String output = reader.lines().collect(Collectors.joining("\n"));

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return truncate(output) + "\n\n[错误：命令执行超时（" + timeout + "秒），已强制终止]";
            }

            int exitCode = process.exitValue();
            if (output.isEmpty() && exitCode == 0) {
                return "命令执行完成（无输出）。";
            }

            String result = truncate(output);
            if (exitCode != 0) {
                result += "\n\n[退出码: " + exitCode + "]";
            }
            return result;

        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * 判断命令是否属于危险操作（修改、删除、写操作）
     */
    private boolean isDangerousCommand(String command) {
        String cmd = command.trim().toLowerCase();

        // 1. 白名单策略：如果是以下开头的，认为是安全的，不需要回调
        if (cmd.startsWith("ls") ||
                cmd.startsWith("pwd") ||
                cmd.startsWith("grep") ||
                cmd.startsWith("find") ||
                cmd.startsWith("cat") ||
                cmd.startsWith("git status") ||
                cmd.startsWith("git log") ||
                cmd.startsWith("git diff") ||
                cmd.startsWith("which")) {
            return false;
        }

        // 2. 黑名单策略：包含以下关键词的，一律拦截
        // rm (删除), mv (移动/改名), mkdir (建目录), touch (建文件), > (写入), >> (追加)
        // git commit/push/reset (改变状态)
        if (cmd.contains("rm ") ||
                cmd.contains("mv ") ||
                cmd.contains("mkdir ") ||
                cmd.contains("touch ") ||
                cmd.contains(">") ||
                cmd.contains("sed -i") ||
                cmd.contains("git commit") ||
                cmd.contains("git push") ||
                cmd.contains("git reset") ||
                cmd.contains("git checkout")) {
            return true;
        }

        // 3. 默认策略：如果不确定，为了安全起见，依然触发回调
        return true;
    }


    /**
     * 辅助方法：获取 Git Bash 的可用路径
     */
    private String getGitBashPath() {
        // 1. 检查几个常见的安装位置
        String[] commonPaths = {
                "D:\\Tool\\Git\\bin\\bash.exe"
        };

        for (String path : commonPaths) {
            File file = new File(path);
            if (file.exists()) {
                return path;
            }
        }

        // 2. 如果都没找到，尝试直接调用 bash (前提是用户配了环境变量)
        if (canRunCommand("bash --version")) {
            return "bash";
        }

        // 3. 最终 fallback：如果还找不到，说明电脑上可能真的没装 Git 或者路径太奇葩
        // 这里建议抛出一个更清晰的异常，或者返回一个错误提示
        return "bash";
    }

    private boolean canRunCommand(String cmd) {
        try {
            Runtime.getRuntime().exec(cmd);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n... [输出已截断，共 " + text.length() + " 字符]";
    }
}
