package com.velinx.core.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velinx.dto.TtsConfigPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TtsConfigStore {

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public TtsConfigStore(Path configPath, ObjectMapper objectMapper) {
        this.configPath = configPath;
        this.objectMapper = objectMapper;
    }

    public void updateConfig(String apiUri, String apiKey, String model, String voice) throws IOException {
        ObjectNode config = readConfig();

        if (apiUri != null) {
            config.put("apiUri", apiUri);
        }
        if (apiKey != null) {
            config.put("apiKey", apiKey);
        }
        if (model != null) {
            config.put("model", model);
        }
        if (voice != null) {
            config.put("voice", voice);
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

    public TtsConfigPayload readTtsConfig() throws IOException {
        ObjectNode config = readConfig();
        return new TtsConfigPayload(
                readText(config, "apiUri"),
                readText(config, "apiKey"),
                readText(config, "model"),
                readText(config, "voice")
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

    private String readText(ObjectNode config, String fieldName) {
        if (config == null || config.get(fieldName) == null || config.get(fieldName).isNull()) {
            return "";
        }
        return config.get(fieldName).asText("");
    }
}
