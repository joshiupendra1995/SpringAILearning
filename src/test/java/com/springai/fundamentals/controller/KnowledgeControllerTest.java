package com.springai.fundamentals.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.springai.fundamentals.model.KnowledgeStatus;
import com.springai.fundamentals.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    private final KnowledgeService service = mock(KnowledgeService.class);
    private final KnowledgeController controller = new KnowledgeController(service);

    @Test
    void reportsStatusWithoutTriggeringIndexing() {
        KnowledgeStatus status = new KnowledgeStatus(false, 0, 0, "test-embeddings", null);
        when(service.status()).thenReturn(status);

        assertSame(status, controller.status());
    }

    @Test
    void defersIndexingToTheBoundedElasticWorker() throws IOException {
        KnowledgeStatus indexed = new KnowledgeStatus(true, 2, 4, "test-embeddings", Instant.now());
        AtomicReference<String> executionThread = new AtomicReference<>();
        when(service.index()).thenAnswer(invocation -> {
            executionThread.set(Thread.currentThread().getName());
            return indexed;
        });

        var result = controller.index();
        verifyNoInteractions(service);
        StepVerifier.create(result).expectNext(indexed).verifyComplete();

        assertTrue(executionThread.get().startsWith("boundedElastic-"));
    }

    @Test
    void preservesConflictsWithoutReplacingTheirStatus() throws IOException {
        ResponseStatusException conflict = new ResponseStatusException(HttpStatus.CONFLICT, "Indexing in progress.");
        when(service.index()).thenThrow(conflict);

        StepVerifier.create(controller.index())
                .expectErrorSatisfies(exception -> assertSame(conflict, exception))
                .verify();
    }

    @Test
    void hidesProviderDetailsAndExplainsMissingEmbeddings() throws IOException {
        when(service.index()).thenThrow(new IOException("provider-secret-internal-address"));

        StepVerifier.create(controller.index())
                .expectErrorSatisfies(exception -> {
                    assertTrue(exception instanceof ResponseStatusException);
                    ResponseStatusException response = (ResponseStatusException) exception;
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertTrue(response.getReason().contains("embedding model"));
                    assertFalse(response.getReason().contains("provider-secret"));
                })
                .verify();
    }
}
