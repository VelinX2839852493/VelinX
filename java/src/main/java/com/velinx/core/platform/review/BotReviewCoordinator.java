package com.velinx.core.platform.review;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 审查函数
 */
public final class BotReviewCoordinator {

    private final ObjectMapper objectMapper;
    private volatile CompletableFuture<Boolean> pendingReview;

    public BotReviewCoordinator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean requestReview(
            String fileName,
            String oldCode,
            String newCode,
            Consumer<String> payloadConsumer,
            Consumer<Exception> errorHandler
    ) {
        CompletableFuture<Boolean> reviewFuture = new CompletableFuture<>();
        pendingReview = reviewFuture;

        try {
            payloadConsumer.accept(buildPayload(fileName, oldCode, newCode));
            return reviewFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorHandler.accept(e);
            return false;
        } catch (Exception e) {
            errorHandler.accept(e);
            return false;
        } finally {
            if (pendingReview == reviewFuture) {
                pendingReview = null;
            }
        }
    }

    public void resolveReview(boolean approved) {
        CompletableFuture<Boolean> reviewFuture = pendingReview;
        if (reviewFuture != null && !reviewFuture.isDone()) {
            reviewFuture.complete(approved);
        }
    }

    public boolean hasPendingReview() {
        CompletableFuture<Boolean> reviewFuture = pendingReview;
        return reviewFuture != null && !reviewFuture.isDone();
    }

    private String buildPayload(String fileName, String oldCode, String newCode) throws Exception {
        Map<String, String> reviewData = new LinkedHashMap<>();
        reviewData.put("fileName", fileName);
        reviewData.put("oldCode", oldCode);
        reviewData.put("newCode", newCode);
        return objectMapper.writeValueAsString(reviewData);
    }
}
