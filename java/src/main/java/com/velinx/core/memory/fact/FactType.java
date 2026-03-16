package com.velinx.core.memory.fact;

public enum FactType {
    USER_PROFILE("user_profile"),
    USER_PREFERENCE("user_preference"),
    RELATIONSHIP("relationship"),
    AI_PROFILE("ai_profile"),
    CONSTRAINT("constraint"),
    PROJECT_CONTEXT("project_context");

    private final String value;

    FactType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FactType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        for (FactType factType : values()) {
            if (factType.value.equalsIgnoreCase(value.trim())) {
                return factType;
            }
        }
        return null;
    }
}
