package com.velinx.core.platform.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器：负责加载、切换和保存角色及 OpenAI 配置
 * <p>
 * 该类管理两类配置：
 * 1. 角色提示词配置：包括通用提示词、更新用户档案、总结提示词等
 * 2. OpenAI API配置：包括API密钥、基础URL、模型名称等
 * <p>
 * 支持多角色独立配置和动态切换机制
 */
public class ConfigManager {

    // ===================== 角色提示词配置属性 =====================
    private String general_prompt;       // 通用提示词 - 定义AI的基本行为和角色
    private String update_user;          // 更新用户档案 - 用于更新用户信息的提示词
    private String update_ai;   // 更新人物档案 - 用于更新AI角色信息的提示词
    private String single_summary;       // 单句总结 - 用于生成单句摘要的提示词
    private String summary_prompt;       // 总体剧情总结 - 用于生成详细摘要的提示词
    private String trust_prompt;         // 事实提取 - 用于从对话中提取事实信息
    private String comparetrust_prompt;  // 事实比对 - 用于比较和验证事实信息
    private String random_profile_prompt;// 角色档案生成器 - 用于生成随机角色档案

    // 当前配置标识和角色名称
    private String CONFIG;               // 存储当前的配置标题 (如 "1")，用于标识当前使用的配置
    private String name;                 // 角色名称 (用于区分不同角色的私有配置)

    // 配置数据存储
    private List<Map<String, Object>> all_role_configs;  // 存储所有角色配置的列表
    private Map<String, Object> OPENAI_API;              // 存储OpenAI API配置的映射
    private Map<String, Object> TTS_API;

    // JSON处理对象
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);  // 启用格式化输出，便于调试

    /**
     * 初始化 ConfigManager
     * <p>
     * 构造函数执行以下步骤：
     * 1. 确保配置文件存在（通过Prompts.ensureInitialized）
     * 2. 加载所有角色配置
     * 3. 加载OpenAI API配置
     * 4. 设置当前配置（支持角色私有配置覆盖）
     *
     * @param name   角色名称，用于区分不同角色的私有配置
     * @param config 默认的配置标题 (如 "1")
     */
    public ConfigManager(String name, String config) {
        this.name = name;
        this.CONFIG = config;

        // 1. 调用外部初始化器：如果磁盘上没有json文件，则创建并写入默认内容
        ConfigWrite.ensureInitialized();

        // 2. 加载所有角色配置
        this.all_role_configs = _load_all_configs();

        // 3. 加载 OpenAI API 配置
        this.OPENAI_API = _load_openai_config();

        this.TTS_API = _load_tts_config();

        // 4. 执行配置切换逻辑（会检查角色目录下的 config.txt 覆盖默认 title）
        set_config(this.CONFIG);
    }

    /**
     * 从物理文件读取角色总配置文件
     * <p>
     * 该方法从PathConfig.CONFIG_FILE_PATH指定的路径加载所有角色配置
     * 使用Jackson进行JSON反序列化，返回List<Map<String, Object>>格式
     *
     * @return 所有角色配置的列表，如果加载失败则返回空列表
     */
    private List<Map<String, Object>> _load_all_configs() {
        File configFile = new File(PathConfig.CONFIG_FILE_PATH);
        try {
            // Prompts.ensureInitialized() 已经保证了文件存在
            return objectMapper.readValue(configFile, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (IOException e) {
            System.err.println("读取角色配置文件失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 从物理文件读取 OpenAI 配置文件
     * <p>
     * 该方法从PathConfig.OPENAI_CONFIG_PATH指定的路径加载OpenAI API配置
     * 使用Jackson进行JSON反序列化，返回Map<String, Object>格式
     *
     * @return OpenAI API配置的映射，如果加载失败则返回空映射
     */
    private Map<String, Object> _load_openai_config() {
        File apiFile = new File(PathConfig.OPENAI_CONFIG_PATH);
        try {
            // Prompts.ensureInitialized() 已经保证了文件存在
            return objectMapper.readValue(apiFile, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            System.err.println("读取 OpenAI 配置文件失败: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, Object> _load_tts_config() {
        File apiFile = new File(PathConfig.TTS_CONFIG_PATH);
        try {
            // Prompts.ensureInitialized() 已经保证了文件存在
            return objectMapper.readValue(apiFile, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            System.err.println("读取 tts 配置文件失败: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 核心切换方法：根据标题设置当前使用的提示词配置
     * <p>
     * 配置优先级：
     * 1. 角色私有目录下的config.txt（最高优先级）
     * 2. 构造函数传入的默认配置标题
     * <p>
     * 该方法还会将当前配置保存到角色私有目录，实现持久化
     *
     * @param configTitle 要切换到的配置标题
     */
    public void set_config(String configTitle) {
        // --- 逻辑：优先检查角色私有目录下的 config.txt ---
        String profilePath = Paths.get(PathConfig.DATA_DIR, this.name, "config.txt").toString();
        File profileFile = new File(profilePath);

        if (profileFile.exists()) {
            try {
                // 如果用户在该角色下手动改过配置标识，则覆盖传入的 configTitle
                configTitle = new String(Files.readAllBytes(profileFile.toPath()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // 如果私有配置不存在，说明是第一次运行，保存当前的 title
            save_config(configTitle);
        }

        // 从内存中的配置列表获取对应的 JSON 项
        Map<String, Object> current_cfg = _get_config_by_title(configTitle);

        // 批量赋值到类属性 - 将配置项映射到实例变量
        this.general_prompt = (String) current_cfg.get("general_prompt");
        this.update_user = (String) current_cfg.get("update_user");
        this.update_ai = (String) current_cfg.get("update_instruction");
        this.summary_prompt = (String) current_cfg.get("summary_prompt");
        this.trust_prompt = (String) current_cfg.get("trust_prompt");
        this.comparetrust_prompt = (String) current_cfg.get("comparetrust_prompt");
        this.random_profile_prompt = (String) current_cfg.get("random_profile_prompt");
        this.single_summary = (String) current_cfg.get("single_summary");

        this.CONFIG = configTitle;
        // 确保角色目录下的标识是最新的
        save_config(this.CONFIG);
    }

    /**
     * 根据标题在配置列表中查找对应的配置项
     * <p>
     * 搜索逻辑：
     * 1. 遍历所有配置项，匹配title字段
     * 2. 如果找到匹配项则返回
     * 3. 如果未找到且列表不为空，则返回第一项作为兜底
     * 4. 如果列表为空则抛出异常
     *
     * @param title 要查找的配置标题
     * @return 匹配的配置项映射，如果未找到则返回第一项或抛出异常
     */
    private Map<String, Object> _get_config_by_title(String title) {
//        System.out.println("Get config by title:!!!!!!!!! " + title);
        for (Map<String, Object> cfgItem : this.all_role_configs) {
            if (title.equals(String.valueOf(cfgItem.get("title")))) {
                return cfgItem;
            }
        }
        throw new RuntimeException("配置文件中未找到 title 为 " + title + " 的配置项，且列表为空");
    }

    /**
     * 保存当前的配置标识（title）到具体角色的目录下
     * <p>
     * 该方法实现配置的持久化存储，确保下次启动时能恢复到当前配置
     * 会在角色目录下创建config.txt文件存储配置标题
     *
     * @param configContent 要保存的配置内容（通常是配置标题）
     */
    public void save_config(String configContent) {
        if (this.name == null || this.name.isEmpty()) return;

        String profilePath = Paths.get(PathConfig.DATA_DIR, this.name, "config.txt").toString();
        File profileFile = new File(profilePath);

        try {
            if (profileFile.getParentFile() != null) {
                profileFile.getParentFile().mkdirs();  // 确保目录存在
            }
            Files.write(profileFile.toPath(), configContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===================== 角色配置 Getters =====================

    /**
     * 获取当前配置标题
     *
     * @return 当前使用的配置标题
     */
    public String get_config() {
        return this.CONFIG;
    }

    /**
     * 获取更新用户档案提示词
     *
     * @return 更新用户档案的提示词内容
     */
    public String get_updateuser_prompt() {
        return this.update_user;
    }

    /**
     * 获取通用提示词
     *
     * @return 通用提示词内容
     */
    public String get_general_prompt() {
        return this.general_prompt;
    }

    /**
     * 获取更新人物档案提示词
     *
     * @return 更新人物档案的提示词内容
     */
    public String get_update_ai() {
        return this.update_ai;
    }

    /**
     * 获取总结提示词
     *
     * @return 总结提示词内容
     */
    public String get_summary_prompt() {
        return this.summary_prompt;
    }

    /**
     * 获取事实提取提示词
     *
     * @return 事实提取提示词内容
     */
    public String get_trust_prompt() {
        return this.trust_prompt;
    }

    /**
     * 获取事实比对提示词
     *
     * @return 事实比对提示词内容
     */
    public String get_comparetrust_prompt() {
        return this.comparetrust_prompt;
    }

    /**
     * 获取单句总结提示词
     *
     * @return 单句总结提示词内容
     */
    public String get_single_summary() {
        return this.single_summary;
    }

    /**
     * 获取角色档案生成器提示词
     *
     * @return 角色档案生成器提示词内容
     */
    public String get_random_profile_prompt() {
        return this.random_profile_prompt;
    }

    /**
     * 获取 OpenAI 配置中的指定项
     * <p>
     * 从OpenAI API配置映射中获取指定键的值
     * 如果配置映射为空或键不存在，则返回"None"
     *
     * @param key 要获取的配置项键名
     * @return 配置项的值，如果不存在则返回"None"
     */
    public String get_openai(String key) {
        if (this.OPENAI_API == null) return "None";
        Object result = this.OPENAI_API.get(key);
        return result != null ? result.toString() : "None";
    }

    public String get_tts(String key) {
        if (this.TTS_API == null) return "None";
        Object result = this.TTS_API.get(key);
        return result != null ? result.toString() : "None";
    }

}
