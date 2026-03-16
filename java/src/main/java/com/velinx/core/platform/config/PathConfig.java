package com.velinx.core.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路径配置类：负责初始化、提供全局路径，并生成路径映射 JSON
 */
public class PathConfig {
    private static final Logger logger = LoggerFactory.getLogger(PathConfig.class);

    public static final String BASE_DIR;
    public static final String ROOT_DIR;
    public static final boolean IS_PACKAGED;

    // 核心路径常量
    public static final String INTERNAL_DATA_DIR;
    public static final String DATA_DIR;
    public static final String MODEL_DIR;
    public static final String CONFIG_FILE_PATH;
    public static final String OPENAI_CONFIG_PATH;
    public static final String TTS_CONFIG_PATH;
    public static final String SKILLS_DIR;

    // --- 新增：路径映射 Map 和 JSON 字符串 ---
    public static final Map<String, String> PATH_MAP = new LinkedHashMap<>();
    public static final String PATH_CONFIG_JSON_PATH;

    static {
        // 1. 环境检测与基础路径计算 (保持原有逻辑)
        String electronOverridePath = System.getenv("JAVA_ROOT_DIR");
        String tempBaseDir;
        String tempRootDir;
        boolean tempIsPackaged;

        if (electronOverridePath != null && !electronOverridePath.isEmpty()) {
            tempRootDir = electronOverridePath;
            tempIsPackaged = true;
            tempBaseDir = getJarLocation();
        } else {
            tempIsPackaged = isRunningFromJar();
            if (tempIsPackaged) {
                String exeDir = getJarLocation();
                String portableDir = System.getenv("PORTABLE_EXECUTABLE_DIR");
                String effectiveRoot = (portableDir != null) ? portableDir : exeDir;
                tempRootDir = Paths.get(effectiveRoot, "java_content").toString();
                tempBaseDir = exeDir;
            } else {
                tempBaseDir = System.getProperty("user.dir");
                tempRootDir = tempBaseDir;
            }
        }

        BASE_DIR = tempBaseDir;
        ROOT_DIR = tempRootDir;
        IS_PACKAGED = tempIsPackaged;

        // 2. 定义具体业务路径
        INTERNAL_DATA_DIR = Paths.get(BASE_DIR, "data").toString();
        DATA_DIR = Paths.get(ROOT_DIR, "data").toString();
        MODEL_DIR = Paths.get(ROOT_DIR, "models").toString();
        CONFIG_FILE_PATH = Paths.get(DATA_DIR, "role_all_configs.json").toString();
        OPENAI_CONFIG_PATH = Paths.get(DATA_DIR, "openai_config.json").toString();
        TTS_CONFIG_PATH = Paths.get(DATA_DIR, "tts_config.json").toString();
        SKILLS_DIR = Paths.get(ROOT_DIR, "skills").toString();

        // JSON 映射文件的保存位置
        PATH_CONFIG_JSON_PATH = Paths.get(BASE_DIR, "path_mapping.json").toString();

        // 3. 将结果存入 Map 映射
        PATH_MAP.put("BASE_DIR", BASE_DIR);
        PATH_MAP.put("ROOT_DIR", ROOT_DIR);
        PATH_MAP.put("INTERNAL_DATA_DIR", INTERNAL_DATA_DIR);
        PATH_MAP.put("DATA_DIR", DATA_DIR);
        PATH_MAP.put("MODEL_DIR", MODEL_DIR);
        PATH_MAP.put("CONFIG_FILE_PATH", CONFIG_FILE_PATH);
        PATH_MAP.put("OPENAI_CONFIG_PATH", OPENAI_CONFIG_PATH);
        PATH_MAP.put("SKILLS_DIR", SKILLS_DIR);
        PATH_MAP.put("IS_PACKAGED", String.valueOf(IS_PACKAGED));

        // 4. 执行初始化操作
        createDirectories(DATA_DIR);
        createDirectories(MODEL_DIR);
        createDirectories(SKILLS_DIR);

        // 5. 持久化到 JSON 文件 (可选，方便 Electron 或外部读取)
        savePathMapToJson();

        logger.info("Path configurations initialized and mapped.");
    }

    /**
     * 将路径映射保存为 JSON 文件
     */
    private static void savePathMapToJson() {
        try {
            // 简单的 JSON 构建逻辑（避免引入额外的库，如果项目有 Jackson/Gson 建议替换）
            String json = "{\n" + PATH_MAP.entrySet().stream()
                    .map(e -> "  \"" + e.getKey() + "\": \"" + e.getValue().replace("\\", "\\\\") + "\"")
                    .collect(Collectors.joining(",\n")) + "\n}";

            Files.write(Paths.get(PATH_CONFIG_JSON_PATH), json.getBytes(StandardCharsets.UTF_8));
            logger.info("Path mapping saved to: " + PATH_CONFIG_JSON_PATH);
        } catch (IOException e) {
            logger.error("Failed to save path mapping json", e);
        }
    }

    // --- 原有辅助方法不变 ---
    private static boolean isRunningFromJar() {
        String protocol = PathConfig.class.getResource("PathConfig.class").getProtocol();
        return "jar".equals(protocol);
    }

    private static String getJarLocation() {
        try {
            File file = new File(PathConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return file.isFile() ? file.getParent() : file.getPath();
        } catch (Exception e) {
            return System.getProperty("user.dir");
        }
    }

    private static void createDirectories(String pathStr) {
        try {
            Files.createDirectories(Paths.get(pathStr));
        } catch (IOException e) {
            System.err.println("无法创建目录: " + pathStr + " -> " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // 测试输出生成的 Map
        PATH_MAP.forEach((k, v) -> System.out.println(k + " = " + v));
    }
}