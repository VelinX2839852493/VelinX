package com.velinx.core.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velinx.dto.ModelConfigPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * opaniconfig的修改类（bot外使用）
 */
public final class BotConfigStore {

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public BotConfigStore(Path configPath, ObjectMapper objectMapper) {
        this.configPath = configPath;
        this.objectMapper = objectMapper;
    }

    public void updateModelConfig(String baseUrl, String apiKey, String modelName) throws IOException {
        ObjectNode config = readConfig();

        if (baseUrl != null) {
            config.put("BASE_URL_b", ModelConfigNormalizer.normalizeBaseUrl(baseUrl));
        }
        if (apiKey != null) {
            config.put("API_KEY_b", apiKey);
        }
        if (modelName != null) {
            config.put("MODEL_NAME_b", modelName);
        }

        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                configPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config),
                StandardCharsets.UTF_8
        );
    }

    public ObjectNode readConfig() throws IOException {
        if (Files.notExists(configPath)) {
            return objectMapper.createObjectNode();
        }

        String content = Files.readString(configPath, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return objectMapper.createObjectNode();
        }

        return (ObjectNode) objectMapper.readTree(content);
    }

    public ModelConfigPayload readModelConfig() throws IOException {
        ObjectNode config = readConfig();
        return new ModelConfigPayload(
                ModelConfigNormalizer.normalizeBaseUrl(readText(config, "BASE_URL_b")),
                readText(config, "API_KEY_b"),
                readText(config, "MODEL_NAME_b")
        );
    }

    private String readText(ObjectNode config, String fieldName) {
        if (config == null || config.get(fieldName) == null || config.get(fieldName).isNull()) {
            return "";
        }
        return config.get(fieldName).asText("");
    }
}
