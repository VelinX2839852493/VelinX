package com.velinx.core.memory.profile;

import com.velinx.core.memory.ProfileManager;
import com.velinx.core.platform.config.ConfigManager;
import com.velinx.core.platform.config.PathConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 主个人挡寒管理类
 */
public class Profile implements ProfileManager {
    private static final Logger logger = LoggerFactory.getLogger(Profile.class);

    // 长期画像存储（系统提示词的补充）
    private String aiProfile = "";      // AI 自身的画像/设定设定
    private String userProfile = "";

    private String name;
    private ChatLanguageModel Model;
    private ConfigManager config;

    // 新增：保存文件的路径，方便后续更新写入
    private Path aiProfilePath;
    private Path userProfilePath;

    public Profile(String name, ChatLanguageModel Model, ConfigManager config) {
        this.name = name;
        this.Model = Model;
        this.config = config;

        init(name);
    }

    private void init(String name) {
        Path botDir = Paths.get(PathConfig.DATA_DIR, name);
        try {
            Files.createDirectories(botDir); // 确保目录存在

            // 记录路径并加载内容
            this.aiProfilePath = botDir.resolve("aiprofile.md");
            this.userProfilePath = botDir.resolve("userprofile.md");

            this.aiProfile = loadOrInitFile(this.aiProfilePath);
            this.userProfile = loadOrInitFile(this.userProfilePath);
        } catch (IOException e) {
            logger.error("加载/创建 profile 失败: ", e);
        }
    }

    // ==================== 新增：动态更新档案的核心逻辑 ====================

    /**
     * 将历史记录发给 AI，让其结合当前档案进行反思，并更新 AI 档案
     * @param formattedChatHistory 格式化好的最近对话历史 (例如: "User: 你好\nAI: 嗨！\nUser: 我喜欢苹果")
     */
    public void updateAiProfileByHistory(String formattedChatHistory) {
        logger.info("开始基于对话历史更新 AI 档案...");

        AiProfileUpdateTask task = new AiProfileUpdateTask(
                config.get_update_ai(),
                this.aiProfile,
                formattedChatHistory
        );

        UserMessage updateTaskMessage = new UserMessage(task.buildPrompt());

        try {
            // 2. 发送给大模型进行思考和总结
            Response<AiMessage> response = Model.generate(updateTaskMessage);
            String newAiProfile = response.content().text().trim();

            // 3. 更新内存中的变量
            this.aiProfile = newAiProfile;

            // 4. 将新档案写入到本地文件，覆盖旧文件
            saveToFile(this.aiProfilePath, this.aiProfile);
            logger.info("AI 档案更新成功！");

        } catch (Exception e) {
            logger.error("更新 AI 档案失败：", e);
        }
    }

    /**
     * 辅助方法：将字符串内容覆盖写入到指定文件中
     */
    private void saveToFile(Path path, String content) {
        try {
            // 使用 TRUNCATE_EXISTING 覆盖旧文件
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("保存文件失败 {}: ", path, e);
        }
    }

    // ==================== 工具方法 ====================
    private String loadOrInitFile(Path path) throws IOException {
        if (Files.exists(path)) {
            logger.info("已加载 profile: {}", path);
            return Files.readString(path).trim();
        } else {
            Files.createFile(path);
            logger.info("已创建空 profile: {}", path);
            return "";
        }
    }

    public String getUserProfile() {
        return userProfile;
    }

    public String getAiProfile() {
        return aiProfile;
    }
}
