package com.velinx.core.platform.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes OpenAI-compatible model base URLs so callers can paste either a
 * provider base URL or a full endpoint URL without breaking downstream SDKs.
 */
public final class ModelConfigNormalizer {

    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String OPENROUTER_WWW_HOST = "www.openrouter.ai";

    private ModelConfigNormalizer() {
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }

        String trimmed = baseUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        try {
            URI uri = URI.create(trimmed);
            String normalizedPath = normalizePath(uri.getHost(), uri.getPath());
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    normalizedPath,
                    null,
                    null
            ).toString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return trimmed;
        }
    }

    private static String normalizePath(String host, String rawPath) {
        String path = rawPath == null || rawPath.isBlank() ? "" : rawPath.trim();
        path = stripTrailingSlash(path);
        if ("/".equals(path)) {
            path = "";
        }
        path = stripKnownEndpointSuffix(path);

        if (isOpenRouterHost(host)) {
            if (path.isBlank()) {
                return "/api/v1";
            }
            if ("/v1".equals(path)) {
                return "/api/v1";
            }
        }

        return path.isBlank() ? null : path;
    }

    private static String stripTrailingSlash(String path) {
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String stripKnownEndpointSuffix(String path) {
        if (path.endsWith("/chat/completions")) {
            return path.substring(0, path.length() - "/chat/completions".length());
        }
        if (path.endsWith("/completions")) {
            return path.substring(0, path.length() - "/completions".length());
        }
        return path;
    }

    private static boolean isOpenRouterHost(String host) {
        return OPENROUTER_HOST.equalsIgnoreCase(host) || OPENROUTER_WWW_HOST.equalsIgnoreCase(host);
    }
}
