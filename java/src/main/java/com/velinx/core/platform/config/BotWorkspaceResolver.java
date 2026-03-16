package com.velinx.core.platform.config;

import java.nio.file.Path;

/**
 * 工具类，处理干净的路径
 */
public final class BotWorkspaceResolver {

    private BotWorkspaceResolver() {
    }

    public static String resolve(String configuredWorkspaceDir) {
        if (configuredWorkspaceDir == null || configuredWorkspaceDir.isBlank()) {
            return PathConfig.ROOT_DIR;
        }
        return Path.of(configuredWorkspaceDir).toAbsolutePath().normalize().toString();
    }
}
