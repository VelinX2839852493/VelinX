package com.velinx.core.memory;

import com.velinx.core.memory.fact.FactCard;
import com.velinx.core.memory.fact.FactCardExtractor;
import com.velinx.core.memory.model.CompletedTurn;
import com.velinx.core.memory.policy.MemoryPolicy;
import com.velinx.core.memory.summary.Summarizer;
import com.velinx.core.platform.config.ConfigManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 总记忆类
 */
public class MemoryManager implements ChatMemory {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryFeatures memoryFeatures;
    private final ShortChatHistoryStore shortTermMemory;
    private final HippocampusManager hippocampus;
    private final KnowledgeBase knowledgeBase;
    private final ProfileManager profile;
    private final Summarizer summarizer;
    private final FactCardExtractor factCardExtractor;
    private final ChatLanguageModel b_Model;
    private final ConfigManager config;
    private final ExecutorService memoryExecutor;

    private final Object stateLock = new Object();
    private final Object queueLock = new Object();
    private final Object longTermMemoryLock = new Object();

    private List<String> tempRetrievedFacts = new ArrayList<>();
    private List<String> tempRetrievedKnowledge = new ArrayList<>();
    private List<ChatMessage> inFlightMessages = new ArrayList<>();
    private final List<CompletedTurn> pendingTurns = new ArrayList<>();

    private boolean workerScheduled = false;
    private final boolean acceptingAsyncTurns = true;
    private boolean turnActive = false;
    private long nextTurnId = 0;

    public MemoryManager(MemoryFeatures memoryFeatures,
                         ShortChatHistoryStore shortTermMemory,
                         Summarizer summarizer,
                         HippocampusManager hippocampus,
                         KnowledgeBase knowledgeBase,
                         FactCardExtractor factCardExtractor,
                         ProfileManager profile,
                         ChatLanguageModel b_Model,
                         ConfigManager config,
                         ExecutorService memoryExecutor) {
        this.memoryFeatures = memoryFeatures != null ? memoryFeatures : new MemoryFeatures();
        this.shortTermMemory = shortTermMemory;
        this.summarizer = summarizer;
        this.hippocampus = hippocampus;
        this.knowledgeBase = knowledgeBase;
        this.factCardExtractor = factCardExtractor;
        this.profile = profile;
        this.b_Model = b_Model;
        this.config = config;
        this.memoryExecutor = memoryExecutor;
    }

    public void prepareTurn(String userInput) {
        List<String> facts = List.of();
        List<String> kb = List.of();

        //检索事实
        if (memoryFeatures.isEnableLongTermRetrieval()
                && MemoryPolicy.shouldRetrieveLongTermMemory(userInput)) {
            synchronized (longTermMemoryLock) {
                facts = hippocampus.retrieveFacts(userInput);
            }
        }

        //检索知识库（向量）
        if (memoryFeatures.isEnableKnowledgeRetrieval()
                && MemoryPolicy.shouldRetrieveKnowledge(userInput)) {
            kb = knowledgeBase.retrieveRelevantKnowledge(userInput);
        }

        synchronized (stateLock) {
            tempRetrievedFacts = new ArrayList<>(facts);
            tempRetrievedKnowledge = new ArrayList<>(kb);
            inFlightMessages = new ArrayList<>();
            turnActive = true;
        }
    }

    public void afterTurn(String userInput, String aiResponse, ChatLanguageModel model) {
        if (aiResponse == null || aiResponse.isBlank()) {
            logger.debug("Skipping async memory write because the AI response is blank");
            return;
        }

        synchronized (queueLock) {
            if (!acceptingAsyncTurns || memoryExecutor.isShutdown()) {
                return;
            }
            pendingTurns.add(new CompletedTurn(++nextTurnId, userInput, aiResponse, model, Instant.now()));
            if (workerScheduled) {
                return;
            }
            workerScheduled = true;
        }
        memoryExecutor.execute(this::runAsyncWorker);
    }

    private void runAsyncWorker() {
        while (true) {
            List<CompletedTurn> batch;
            synchronized (queueLock) {
                if (pendingTurns.isEmpty()) {
                    workerScheduled = false;
                    return;
                }
                batch = new ArrayList<>(pendingTurns);
                pendingTurns.clear();
            }
            try {
                processBatch(batch);
            } catch (Exception e) {
                logger.error("Failed to process memory batch", e);
            }
        }
    }

    private void processBatch(List<CompletedTurn> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        ChatLanguageModel model = b_Model;

        //总结
        if (memoryFeatures.isEnableSummary()) {
            processSummary(model);
        }

        //总结事实
        if (memoryFeatures.isEnableFactExtraction()) {
            String latestSummary;
            synchronized (stateLock) {
                latestSummary = summarizer.getLatestSummary();
            }

            List<FactCard> facts = factCardExtractor.extractBatch(batch, latestSummary, model);
            if (!facts.isEmpty()) {
                synchronized (longTermMemoryLock) {
                    hippocampus.upsertFacts(facts);
                }
            }
        }

//        画像更新
        if (memoryFeatures.isEnableProfileUpdate()) {
            String history;
            synchronized (stateLock) {
                String summaryText = memoryFeatures.isEnableSummary() ? summarizer.getLatestSummary() : "";
                history = "摘要: " + summaryText + "\n历史: " + shortTermMemory.getConversationHistoryAsPrompt();
            }
            profile.updateAiProfileByHistory(history);
        }
    }

    private boolean processSummary(ChatLanguageModel model) {
        List<ChatMessage> snapshot;
        synchronized (stateLock) {
            snapshot = new ArrayList<>(shortTermMemory.getAllMessages());
        }

        var res = summarizer.summarizePreview(config.get_summary_prompt(), model, snapshot, 10, 6);
        if (res == null) {
            return false;
        }

        synchronized (stateLock) {
            shortTermMemory.refresh(res.keptMessages());
            summarizer.updateSummary(res.summary());
        }
        return true;
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> committedMessages;
        List<ChatMessage> currentTurnMessages;
        String summary;
        List<String> facts;
        List<String> kb;

        synchronized (stateLock) {
            committedMessages = new ArrayList<>(shortTermMemory.getAllMessages());
            currentTurnMessages = new ArrayList<>(inFlightMessages);
            summary = summarizer.getLatestSummary();
            facts = new ArrayList<>(tempRetrievedFacts);
            kb = new ArrayList<>(tempRetrievedKnowledge);
        }

        List<ChatMessage> allMessages = new ArrayList<>(committedMessages.size() + currentTurnMessages.size());
        allMessages.addAll(committedMessages);
        allMessages.addAll(currentTurnMessages);

        List<ChatMessage> result = new ArrayList<>();
        allMessages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .reduce((ignored, latest) -> latest)
                .ifPresent(result::add);

        if (memoryFeatures.isEnableSummary() && summary != null && !summary.isEmpty()) {
            result.add(SystemMessage.from("【历史总结】\n" + summary));
        }
        if (memoryFeatures.isEnableLongTermRetrieval() && !facts.isEmpty()) {
            result.add(SystemMessage.from("【长期记忆】\n- " + String.join("\n- ", facts)));
        }
        if (memoryFeatures.isEnableKnowledgeRetrieval() && !kb.isEmpty()) {
            result.add(SystemMessage.from("【知识库】\n- " + String.join("\n- ", kb)));
        }

        allMessages.stream().filter(m -> !(m instanceof SystemMessage)).forEach(result::add);
        return result;
    }

    @Override
    public void add(ChatMessage m) {
        synchronized (stateLock) {
            if (turnActive) {
                inFlightMessages.add(m);
                return;
            }
            shortTermMemory.add(m);
        }
    }

    public void commitTurn() {
        synchronized (stateLock) {
            if (!turnActive) {
                return;
            }

            List<ChatMessage> committedMessages = inFlightMessages.stream()
                    .map(ChatMessageTextExtractor::sanitizeForHistory)
                    .filter(Objects::nonNull)
                    .toList();

            for (ChatMessage committedMessage : committedMessages) {
                shortTermMemory.add(committedMessage);
            }

            inFlightMessages = new ArrayList<>();
            turnActive = false;
        }
    }

    public void abortTurn() {
        synchronized (stateLock) {
            inFlightMessages = new ArrayList<>();
            turnActive = false;
        }
    }

    @Override
    public void clear() {
        synchronized (stateLock) {
            shortTermMemory.clear();
            tempRetrievedFacts = new ArrayList<>();
            tempRetrievedKnowledge = new ArrayList<>();
            inFlightMessages = new ArrayList<>();
            turnActive = false;
        }
    }

    @Override
    public Object id() {
        return "chat-memory";
    }
}
