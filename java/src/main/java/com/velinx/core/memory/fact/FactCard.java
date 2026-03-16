package com.velinx.core.memory.fact;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

public record FactCard(String factKey,
                       String text,
                       FactType factType,
                       String timestamp,
                       String source,
                       double confidence) {

    private static final String DEFAULT_SOURCE = "conversation_turn";

    public FactCard {
        if (factKey == null || factKey.isBlank()) {
            throw new IllegalArgumentException("factKey must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (factType == null) {
            throw new IllegalArgumentException("factType must not be null");
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("timestamp must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static FactCard create(String text, FactType factType, double confidence) {
        return create(text, factType, confidence, DEFAULT_SOURCE);
    }

    public static FactCard create(String text, FactType factType, double confidence, String source) {
        String normalizedText = normalizeText(text);
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("normalized text must not be blank");
        }

        String factKey = stableFactKey(factType, normalizedText);
        return new FactCard(
                factKey,
                text.trim(),
                factType,
                LocalDateTime.now().toString(),
                source == null || source.isBlank() ? DEFAULT_SOURCE : source.trim(),
                confidence
        );
    }

    public TextSegment toTextSegment() {
        Metadata metadata = new Metadata()
                .put("fact_key", factKey)
                .put("fact_type", factType.value())
                .put("timestamp", timestamp)
                .put("source", source)
                .put("confidence", confidence);
        return TextSegment.from(text, metadata);
    }

    public static FactCard fromTextSegment(TextSegment textSegment) {
        Metadata metadata = textSegment.metadata();
        FactType factType = FactType.fromValue(metadata.getString("fact_type"));
        if (factType == null) {
            throw new IllegalArgumentException("Unknown fact type in metadata");
        }
        return new FactCard(
                metadata.getString("fact_key"),
                textSegment.text(),
                factType,
                metadata.getString("timestamp"),
                metadata.getString("source"),
                metadata.getDouble("confidence")
        );
    }

    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[。！!？?，,；;：“”\"'、】【\\[\\]()（）]", "");
    }

    private static String stableFactKey(FactType factType, String normalizedText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((factType.value() + ":" + normalizedText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
