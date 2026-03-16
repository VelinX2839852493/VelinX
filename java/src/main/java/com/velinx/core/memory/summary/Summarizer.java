package com.velinx.core.memory.summary;

import com.velinx.core.memory.ChatMessageTextExtractor;
import com.velinx.core.memory.SummaryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 总结类
 */
public class Summarizer {

    private static final Logger logger = LoggerFactory.getLogger(Summarizer.class);

    private String latestSummary;
    private final SummaryRepository repository;

    public Summarizer(SummaryRepository repository) {
        this.repository = repository;
        this.latestSummary = repository.load();
    }

    public List<ChatMessage> summarizeIfNeeded(String sumPrompt,
                                               ChatLanguageModel model,
                                               List<ChatMessage> allMessages,
                                               int triggerMessages,
                                               int keepRecentMessages) {
        SummaryResult result = summarizePreview(sumPrompt, model, allMessages, triggerMessages, keepRecentMessages);
        if (result == null) {
            return null;
        }

        updateSummary(result.summary());
        return result.keptMessages();
    }

    public SummaryResult summarizePreview(String sumPrompt,
                                          ChatLanguageModel model,
                                          List<ChatMessage> allMessages,
                                          int triggerMessages,
                                          int keepRecentMessages) {
        SummaryPlan plan = planSummary(allMessages, triggerMessages, keepRecentMessages);
        if (plan == null || model == null) {
            return null;
        }

        String prompt = buildSummaryInput(plan, sumPrompt);

        try {
            String newSummary = model.generate(List.of(UserMessage.from(prompt))).content().text();
            return new SummaryResult(
                    newSummary,
                    plan.keptMessages(),
                    plan.summarizedMessageCount(),
                    plan.keptConversationMessageCount()
            );
        } catch (Exception e) {
            logger.error("Failed to generate summary", e);
            return null;
        }
    }

    public void updateSummary(String summary) {
        this.latestSummary = summary;
        this.repository.save(summary);
    }

    SummaryPlan planSummary(List<ChatMessage> allMessages, int triggerMessages, int keepRecentMessages) {
        if (triggerMessages <= keepRecentMessages) {
            throw new IllegalArgumentException("Threshold error");
        }

        List<ChatMessage> conversationMessages = allMessages.stream()
                .filter(Summarizer::isConversationMessage)
                .toList();

        if (conversationMessages.size() <= triggerMessages) {
            return null;
        }

        int summarizeCount = conversationMessages.size() - keepRecentMessages;
        if (summarizeCount % 2 != 0) {
            summarizeCount--;
        }
        if (summarizeCount <= 0) {
            return null;
        }

        int cutoffIndex = findConversationCutoffIndex(allMessages, summarizeCount);
        if (cutoffIndex < 0) {
            return null;
        }

        List<ChatMessage> keptMessages = new ArrayList<>();
        allMessages.stream()
                .filter(message -> message instanceof SystemMessage)
                .findFirst()
                .ifPresent(keptMessages::add);
        for (int i = cutoffIndex + 1; i < allMessages.size(); i++) {
            if (!(allMessages.get(i) instanceof SystemMessage)) {
                keptMessages.add(allMessages.get(i));
            }
        }

        return new SummaryPlan(
                conversationMessages.subList(0, summarizeCount),
                keptMessages,
                summarizeCount,
                conversationMessages.size() - summarizeCount
        );
    }

    private int findConversationCutoffIndex(List<ChatMessage> allMessages, int summarizeCount) {
        int seen = 0;
        for (int i = 0; i < allMessages.size(); i++) {
            if (!isConversationMessage(allMessages.get(i))) {
                continue;
            }
            seen++;
            if (seen == summarizeCount) {
                return i;
            }
        }
        return -1;
    }

    static boolean isConversationMessage(ChatMessage message) {
        if (message instanceof UserMessage) {
            return true;
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text() != null && !aiMessage.text().isBlank() && !aiMessage.hasToolExecutionRequests();
        }
        return false;
    }

    private String buildSummaryInput(SummaryPlan plan, String sumPrompt) {
        StringBuilder sb = new StringBuilder();
        if (!latestSummary.isEmpty()) {
            sb.append("Existing summary:\n").append(latestSummary).append("\n\n");
        }
        sb.append("New dialogue to summarize:\n");
        for (ChatMessage msg : plan.messagesToSummarize()) {
            String role = (msg instanceof UserMessage) ? "User" : "Assistant";
            sb.append(role).append(": ").append(ChatMessageTextExtractor.extract(msg)).append("\n");
        }
        return sumPrompt + "\n" + sb;
    }

    public String getLatestSummary() {
        return latestSummary;
    }

    static record SummaryPlan(List<ChatMessage> messagesToSummarize,
                              List<ChatMessage> keptMessages,
                              int summarizedMessageCount,
                              int keptConversationMessageCount) {
    }

    public record SummaryResult(String summary,
                                List<ChatMessage> keptMessages,
                                int summarizedMessageCount,
                                int keptConversationMessageCount) {
    }
}
