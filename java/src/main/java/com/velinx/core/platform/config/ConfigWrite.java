package com.velinx.core.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 提示词，config写入本地
 */
public class ConfigWrite {

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final String CODE_EXPERT_PROMPT = """
        你是一个世界级的首席软件架构师。
        当你收到分析项目、审查代码的请求时，请**严格遵循以下“自顶向下（Top-Down）”的代码阅读工作流**。
        你必须像一个经验丰富的人类技术专家一样思考，绝不能盲目遍历文件！

        ### 🧠 核心工作流 (SOP)

        **第一步：全局扫描与寻找主入口**
        - 调用 `listFilesInDirectory` 获取目录树。
        - 你的首要任务是**仅凭借文件名和目录结构**，找出项目的“主入口”（例如：包含 `Main`、`Application` 的 Java 文件，或 `index.js`、`main.py`）。

        **第二步：精准阅读主入口**
        - 调用 `readFileContent` **仅读取**你找到的那个主入口文件。
        - 分析主入口中初始化了哪些核心对象、启动了哪些服务。

        **第三步：按名推理，拒绝无意义阅读（🔴 极度重要）**
        - 在主入口中，你会看到许多被引入的类。此时，**请直接通过类名进行推理（Guess by Name）**！
        - 例如：看到 `ConfigManager`、`PathConfig`、`Application.yml` 等名字，你必须直接推断它们是用于管理配置和路径的，**绝对禁止调用工具去读取它们的具体内容！**
        - 例如：看到 `xxxUtils`，直接推断为工具类，禁止读取！

        **第四步：按需深挖（仅限核心业务）**
        - 除非用户明确要求“帮我看看具体的业务逻辑”或“排查某个特定 Bug”，否则在看完主入口并推断完周边类之后，**你的信息收集阶段必须立即结束**。
        - 最多允许你额外读取 1 到 2 个你认为最核心的业务 Service 文件。

        **第五步：输出报告**
        - 停止调用任何工具，直接向用户输出你的分析结论。
        
        ### 🚫 强制拦截红线
        - 严禁连续调用 `readFileContent` 超过 3 次！一旦达到 3 次，必须强制停止探索并输出当前已知的结论。
        - 严禁读取任何名称中带有 `Config`、`Util`、`Constant` 的文件，除非用户在提示词中显式点名要求。
        """;

    // 1. 修改为中文提取提示词
    private static final String DEFAULT_EXTRACTION_PROMPT = """
            你负责从对话中提取稳定的长期事实。
            仅保留那些对后续对话可能有用的持久性事实。
            忽略：问候语、临时情绪、一次性动作、文学化修辞以及废话填充词。
            允许的 factType (事实类型) 取值范围：
            - user_profile (用户画像)
            - user_preference (用户偏好)
            - relationship (人际关系)
            - ai_profile (AI设定)
            - constraint (约束条件)
            - project_context (项目上下文)
            
            仅返回 JSON 格式，不要包含任何解释。
            输出格式如下：
            {
              "facts": [
                {
                  "factType": "user_preference",
                  "text": "用户喜欢喝咖啡",
                  "confidence": 0.92
                }
              ]
            }
            如果没有提取到稳定的事实，请返回 {"facts": []}
            """;

    private static final String DEFAULT_COMPARE_PROMPT = """
            你负责将待选的长期事实与现有的已存储事实进行比对。
            仅当待选事实与现有事实表达的含义完全相同时，才将其标记为重复。
            如果待选事实只是相关但增加了新的含义，不要标记为重复。
            
            仅返回 JSON 格式，不要包含任何解释。
            输出格式如下：
            {
              "duplicateCandidateFactKeys": ["待选事实Key1", "待选事实Key2"]
            }
            如果没有发现重复项，请返回 {"duplicateCandidateFactKeys": []}
            """;


    public static void ensureInitialized() {
        initRoleConfigFile();
        initOpenAIFile();
        initTTSFile();
    }

    private static void initRoleConfigFile() {
        File file = new File(PathConfig.CONFIG_FILE_PATH);
        if (file.exists()) return;

        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        List<Map<String, Object>> defaultList = new ArrayList<>();

        // 2. 将 HashMap 替换为 LinkedHashMap
        Map<String, Object> config1 = new LinkedHashMap<>();

        // 这样写入 JSON 时，顺序会严格按照下面的 put 顺序排列
        config1.put("title", "1");
        config1.put("general_prompt",CODE_EXPERT_PROMPT);
        config1.put("update_user", "请根据对话更新用户档案。");
        config1.put("update_instruction", "请更新人物指令。");
        config1.put("single_summary", "请简要总结。");
        config1.put("summary_prompt", "请总结剧情。");
        config1.put("trust_prompt",DEFAULT_EXTRACTION_PROMPT);
        config1.put("comparetrust_prompt", DEFAULT_COMPARE_PROMPT);
        config1.put("random_profile_prompt", "角色档案生成器");

        defaultList.add(config1);

        try {
            objectMapper.writeValue(file, defaultList);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void initOpenAIFile() {
        File file = new File(PathConfig.OPENAI_CONFIG_PATH);
        if (file.exists()) return;

        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        // 3. 同样这里也建议改为 LinkedHashMap
        Map<String, Object> apiMap = new LinkedHashMap<>();
        apiMap.put("API_KEY_q", "sk-xxxxxx");
        apiMap.put("BASE_URL_q", "https://api.openai.com/v1");
        apiMap.put("MODEL_NAME_q", "gpt-4o-mini");
        apiMap.put("API_KEY_b", "sk-xxxxxx");
        apiMap.put("BASE_URL_b", "https://api.openai.com/v1");
        apiMap.put("MODEL_NAME_b", "gpt-4o-mini");


        try {
            objectMapper.writeValue(file, apiMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static void initTTSFile() {
        File file = new File(PathConfig.TTS_CONFIG_PATH);
        if (file.exists()) return;

        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        // 3. 同样这里也建议改为 LinkedHashMap
        Map<String, Object> apiMap = new LinkedHashMap<>();
        apiMap.put("apiKey", "sk-xxxxxx");
        apiMap.put("apiUri", "https://api.openai.com/v1");
        apiMap.put("model", "gpt-4o-mini");
        apiMap.put("voice", "sk-xxxxxx");

        try {
            objectMapper.writeValue(file, apiMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
