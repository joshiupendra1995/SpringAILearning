package com.springai.fundamentals.model;

import java.time.Instant;

public record KnowledgeStatus(
        boolean indexed, int documentCount, int chunkCount, String embeddingModel, Instant indexedAt) {
}
