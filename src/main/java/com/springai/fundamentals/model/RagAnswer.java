package com.springai.fundamentals.model;

import java.util.List;
import org.springframework.ai.document.Document;

/** The generated answer and the evidence supplied during that same RAG request. */
public record RagAnswer(String question, String answer, List<Document> retrievedDocuments) {

    public RagAnswer {
        retrievedDocuments = List.copyOf(retrievedDocuments);
    }
}
