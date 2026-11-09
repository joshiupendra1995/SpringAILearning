package com.springai.fundamentals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = "spring.ai.ollama.base-url=http://127.0.0.1:1")
class SpringAiFundamentalsApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    @Test
    void reportsAnUnindexedKnowledgeBaseWithoutOllama() {
        client().get().uri("/api/knowledge")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.indexed").isEqualTo(false)
                .jsonPath("$.documentCount").isEqualTo(0)
                .jsonPath("$.chunkCount").isEqualTo(0)
                .jsonPath("$.embeddingModel").isEqualTo("nomic-embed-text");
    }

    @Test
    void rejectsUnsupportedModesThroughTheHttpApi() {
        client().post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"Hello\",\"mode\":\"unknown\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Mode must be tutor, rag, or tools.");
    }

    @Test
    void streamsAHelpfulErrorWhenRagIsUsedBeforeIndexing() {
        client().post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"message\":\"What is RAG?\",\"mode\":\"rag\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:error"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("Could not prepare the learning notes"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:done"));
                });
    }

    @Test
    void validatesTopicChoicesThroughTheHttpApi() {
        client().post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"message\":\"What is RAG?\",\"mode\":\"rag\",\"topic\":\"unknown\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Topic must be all, rag, spring-ai, or tools.");
    }

    @Test
    void acceptsAnAllowedTopicAndReportsIndexPreparationFailure() {
        client().post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"message\":\"How do tools work?\",\"mode\":\"rag\",\"topic\":\"tools\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:error"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("Could not prepare the learning notes"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:done"));
                });
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(applicationContext).build();
    }
}
