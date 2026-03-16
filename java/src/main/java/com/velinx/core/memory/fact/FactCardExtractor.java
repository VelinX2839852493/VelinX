package com.velinx.core.memory.fact;

import com.velinx.core.memory.model.CompletedTurn;
import com.velinx.core.platform.config.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * 事实卡片
 */
public class FactCardExtractor {
    private static final Logger logger = LoggerFactory.getLogger(FactCardExtractor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 1. 修改为中文提取提示词
    private static String DEFAULT_EXTRACTION_PROMPT = "";

    // 2. 修改为中文比对提示词
    private static String DEFAULT_COMPARE_PROMPT = "";

    private final ConfigManager config;

    public FactCardExtractor(ConfigManager config) {
        this.config = config;
        DEFAULT_EXTRACTION_PROMPT = config.get_trust_prompt();
        DEFAULT_COMPARE_PROMPT =  config.get_comparetrust_prompt();
    }

    public List<FactCard> extract(String userInput,
                                  String aiResponse,
                                  String latestSummary,
                                  ChatLanguageModel model) {
        if (model == null) {
            return List.of();
        }

        String raw = model.generate(List.of(
                SystemMessage.from(buildExtractionPrompt()),
                UserMessage.from(buildSingleTurnExtractionPayload(userInput, aiResponse, latestSummary))
        )).content().text();

        return parseFacts(raw);
    }

    public List<FactCard> extractBatch(List<CompletedTurn> turns,
                                       String latestSummary,
                                       ChatLanguageModel model) {
        if (turns == null || turns.isEmpty() || model == null) {
            return List.of();
        }

        String raw = model.generate(List.of(
                SystemMessage.from(buildExtractionPrompt()),
                UserMessage.from(buildBatchExtractionPayload(turns, latestSummary))
        )).content().text();

        return parseFacts(raw);
    }

    public boolean isSemanticallyDuplicate(FactCard candidate,
                                           List<FactCard> similarFacts,
                                           ChatLanguageModel model) {
        if (candidate == null) {
            return false;
        }
        return findSemanticDuplicateFactKeys(List.of(candidate), similarFacts, model)
                .contains(candidate.factKey());
    }

    public List<String> findSemanticDuplicateFactKeys(List<FactCard> candidates,
                                                      List<FactCard> similarFacts,
                                                      ChatLanguageModel model) {
        if (candidates == null || candidates.isEmpty() || similarFacts == null || similarFacts.isEmpty() || model == null) {
            return List.of();
        }

        String raw = model.generate(List.of(
                SystemMessage.from(buildComparePrompt()),
                UserMessage.from(buildComparePayload(candidates, similarFacts))
        )).content().text();

        JsonNode node = parseJson(stripCodeFence(raw));
        if (node == null) {
            return List.of();
        }

        JsonNode duplicatesNode = node.path("duplicateCandidateFactKeys");
        if (!duplicatesNode.isArray()) {
            return List.of();
        }

        Set<String> duplicateKeys = new LinkedHashSet<>();
        for (JsonNode duplicateNode : duplicatesNode) {
            String factKey = duplicateNode.asText("").trim();
            if (!factKey.isBlank()) {
                duplicateKeys.add(factKey);
            }
        }
        return List.copyOf(duplicateKeys);
    }

    private List<FactCard> parseFacts(String raw) {
        JsonNode node = parseJson(stripCodeFence(raw));
        if (node == null) {
            return List.of();
        }

        JsonNode factsNode = node.isArray() ? node : node.path("facts");
        if (!factsNode.isArray()) {
            return List.of();
        }

        List<FactCard> result = new ArrayList<>();
        for (JsonNode factNode : factsNode) {
            FactType factType = FactType.fromValue(factNode.path("factType").asText(null));
            String text = factNode.path("text").asText("").trim();
            double confidence = factNode.path("confidence").asDouble(0.8);

            if (factType == null || text.isBlank()) {
                continue;
            }

            try {
                result.add(FactCard.create(text, factType, confidence));
            } catch (IllegalArgumentException e) {
                logger.debug("跳过无效的事实候选内容: {}", text);
            }
        }
        return result;
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception e) {
            logger.warn("事实提取器返回了非 JSON 格式的内容: {}", raw);
            return null;
        }
    }

    private String buildExtractionPrompt() {
        String configPrompt = trimToEmpty(config.get_trust_prompt());
        return configPrompt.isBlank() ? DEFAULT_EXTRACTION_PROMPT : configPrompt + "\n\n" + DEFAULT_EXTRACTION_PROMPT;
    }

    private String buildComparePrompt() {
        String configPrompt = trimToEmpty(config.get_comparetrust_prompt());
        return configPrompt.isBlank() ? DEFAULT_COMPARE_PROMPT : configPrompt + "\n\n" + DEFAULT_COMPARE_PROMPT;
    }

    // 3. 修改数据负载的标签为中文
    private String buildSingleTurnExtractionPayload(String userInput, String aiResponse, String latestSummary) {
        return """
                [历史摘要]
                %s

                [当前轮次 - 用户输入]
                %s

                [当前轮次 - 助手回复]
                %s
                """.formatted(trimToEmpty(latestSummary), trimToEmpty(userInput), trimToEmpty(aiResponse));
    }

    private String buildBatchExtractionPayload(List<CompletedTurn> turns, String latestSummary) {
        StringBuilder turnsBuilder = new StringBuilder();
        for (CompletedTurn turn : turns) {
            turnsBuilder.append("轮次ID=").append(turn.turnId()).append("\n")
                    .append("用户: ").append(trimToEmpty(turn.userInput())).append("\n")
                    .append("助手: ").append(trimToEmpty(turn.aiResponse())).append("\n\n");
        }

        return """
                [历史摘要]
                %s

                [对话轮次列表]
                %s
                """.formatted(trimToEmpty(latestSummary), turnsBuilder);
    }

    private String buildComparePayload(List<FactCard> candidates, List<FactCard> similarFacts) {
        StringBuilder candidateFacts = new StringBuilder();
        for (FactCard candidate : candidates) {
            candidateFacts.append("- 事实Key=").append(candidate.factKey())
                    .append(", 事实类型=").append(candidate.factType().value())
                    .append(", 内容=").append(candidate.text())
                    .append("\n");
        }

        StringBuilder existingFacts = new StringBuilder();
        for (FactCard similarFact : similarFacts) {
            existingFacts.append("- 事实Key=").append(similarFact.factKey())
                    .append(", 事实类型=").append(similarFact.factType().value())
                    .append(", 内容=").append(similarFact.text())
                    .append("\n");
        }

        return """
                [待选事实列表]
                %s

                [已存在事实列表]
                %s
                """.formatted(candidateFacts, existingFacts);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String result = raw.trim();
        // 移除 Markdown 的代码块标记 ```json ... ```
        if (result.startsWith("```")) {
            int firstLineBreak = result.indexOf('\n');
            if (firstLineBreak >= 0) {
                result = result.substring(firstLineBreak + 1);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
        }
        return result.trim();
    }
}