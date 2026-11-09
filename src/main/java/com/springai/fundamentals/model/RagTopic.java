package com.springai.fundamentals.model;

/** A relevance filter over the public bundled notes, not an authorization boundary. */
public enum RagTopic {
    ALL("all"), RAG("rag"), SPRING_AI("spring-ai"), TOOLS("tools");

    private final String value;

    RagTopic(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RagTopic fromValue(String value) {
        if (value == null) {
            return ALL;
        }
        return switch (value) {
            case "all" -> ALL;
            case "rag" -> RAG;
            case "spring-ai" -> SPRING_AI;
            case "tools" -> TOOLS;
            default -> throw new IllegalArgumentException("Topic must be all, rag, spring-ai, or tools.");
        };
    }
}
