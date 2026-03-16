package com.velinx.core.memory;

public class MemoryFeatures {
    private boolean enableSummary = true;
    private boolean enableFactExtraction = true;
    private boolean enableProfileUpdate = true;
    private boolean enableKnowledgeRetrieval = true;
    private boolean enableLongTermRetrieval = true;

    public boolean isEnableSummary() {
        return enableSummary;
    }

    public void setEnableSummary(boolean enableSummary) {
        this.enableSummary = enableSummary;
    }

    public boolean isEnableFactExtraction() {
        return enableFactExtraction;
    }

    public void setEnableFactExtraction(boolean enableFactExtraction) {
        this.enableFactExtraction = enableFactExtraction;
    }

    public boolean isEnableProfileUpdate() {
        return enableProfileUpdate;
    }

    public void setEnableProfileUpdate(boolean enableProfileUpdate) {
        this.enableProfileUpdate = enableProfileUpdate;
    }

    public boolean isEnableKnowledgeRetrieval() {
        return enableKnowledgeRetrieval;
    }

    public void setEnableKnowledgeRetrieval(boolean enableKnowledgeRetrieval) {
        this.enableKnowledgeRetrieval = enableKnowledgeRetrieval;
    }

    public boolean isEnableLongTermRetrieval() {
        return enableLongTermRetrieval;
    }

    public void setEnableLongTermRetrieval(boolean enableLongTermRetrieval) {
        this.enableLongTermRetrieval = enableLongTermRetrieval;
    }
}
