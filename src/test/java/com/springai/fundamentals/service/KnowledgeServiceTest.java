package com.springai.fundamentals.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.springai.fundamentals.model.KnowledgeStatus;
import com.springai.fundamentals.model.RagTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    private static final String RESOURCE_PATTERN = "classpath*:knowledge/*.txt";

    private ResourcePatternResolver resources;
    private DeterministicEmbeddings embeddings;

    @BeforeEach
    void setUp() {
        resources = mock(ResourcePatternResolver.class);
        embeddings = new DeterministicEmbeddings();
    }

    @Test
    void statusAndUnindexedSearchNeverCallTheEmbeddingProvider() {
        KnowledgeService service = service(3, 0.45);

        KnowledgeStatus status = service.status();

        assertFalse(status.indexed());
        assertEquals(0, status.documentCount());
        assertEquals(0, status.chunkCount());
        assertEquals("test-embeddings", status.embeddingModel());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.search("ALPHA"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.retrieval(RagTopic.RAG));
        assertEquals(0, embeddings.documentCalls.get());
        assertEquals(0, embeddings.queryCalls.get());
        verifyNoInteractions(resources);
    }

    @Test
    void preparesTheIndexOnFirstUseAndReusesItForLaterQuestions() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is a focused learning note prepared on the first question.")
        });
        KnowledgeService service = service(3, 0.45);
        assertFalse(service.status().indexed());
        assertEquals(0, embeddings.documentCalls.get());

        KnowledgeStatus first = service.ensureIndexed();
        KnowledgeStatus second = service.ensureIndexed();

        assertTrue(first.indexed());
        assertSame(first, second);
        assertSame(first, service.status());
        assertEquals(1, embeddings.documentCalls.get());
        assertEquals(0, embeddings.queryCalls.get());
        verify(resources, times(1)).getResources(RESOURCE_PATTERN);
    }

    @Test
    void concurrentFirstQuestionsShareOneIndexBuild() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is a learning note shared by simultaneous first questions.")
        });
        KnowledgeService service = service(3, 0.45);
        pauseEmbeddings();
        CompletableFuture<KnowledgeStatus> owner = asynchronously(service::ensureIndexed);
        assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<KnowledgeStatus> joined = new CompletableFuture<>();
        Thread waiter = startWaiter(service, joined);

        try {
            awaitWaiting(waiter);
            assertFalse(joined.isDone());
            assertEquals(1, embeddings.documentCalls.get());
        } finally {
            embeddings.resume.countDown();
        }

        assertSame(owner.get(5, TimeUnit.SECONDS), joined.get(5, TimeUnit.SECONDS));
        waiter.join(5_000);
        assertEquals(1, embeddings.documentCalls.get());
        verify(resources, times(1)).getResources(RESOURCE_PATTERN);
    }

    @Test
    void firstQuestionJoinsAManualInitialBuildInsteadOfConflicting() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is a learning note being indexed by the rebuild endpoint.")
        });
        KnowledgeService service = service(3, 0.45);
        pauseEmbeddings();
        CompletableFuture<KnowledgeStatus> manual = asynchronously(service::index);
        assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<KnowledgeStatus> joined = new CompletableFuture<>();
        Thread waiter = startWaiter(service, joined);

        try {
            awaitWaiting(waiter);
            assertFalse(joined.isDone());
            ResponseStatusException conflict = assertThrows(ResponseStatusException.class, service::index);
            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        } finally {
            embeddings.resume.countDown();
        }

        assertSame(manual.get(5, TimeUnit.SECONDS), joined.get(5, TimeUnit.SECONDS));
        waiter.join(5_000);
        assertEquals(1, embeddings.documentCalls.get());
        verify(resources, times(1)).getResources(RESOURCE_PATTERN);
    }

    @Test
    void sharedInitialFailureReachesAllWaitingQuestionsAndTheNextQuestionCanRetry() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is a learning note whose first embedding attempt will fail.")
        });
        KnowledgeService service = service(3, 0.45);
        pauseEmbeddings();
        embeddings.failDocuments = true;
        CompletableFuture<KnowledgeStatus> owner = asynchronously(service::ensureIndexed);
        assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<KnowledgeStatus> joined = new CompletableFuture<>();
        Thread waiter = startWaiter(service, joined);

        try {
            awaitWaiting(waiter);
        } finally {
            embeddings.resume.countDown();
        }
        ExecutionException ownerFailure = assertThrows(ExecutionException.class,
                () -> owner.get(5, TimeUnit.SECONDS));
        ExecutionException joinedFailure = assertThrows(ExecutionException.class,
                () -> joined.get(5, TimeUnit.SECONDS));
        assertSame(ownerFailure.getCause(), joinedFailure.getCause());
        assertFalse(service.status().indexed());
        waiter.join(5_000);

        embeddings.failDocuments = false;
        assertTrue(service.ensureIndexed().indexed());
        assertEquals(2, embeddings.documentCalls.get());
        verify(resources, times(2)).getResources(RESOURCE_PATTERN);
    }

    @Test
    void questionsKeepUsingThePublishedIndexWhileAManualRebuildRuns() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN))
                .thenReturn(new Resource[] { note("old.txt", "ALPHA is the currently published learning note.") })
                .thenReturn(new Resource[] { note("new.txt", "ALPHA is the replacement learning note being prepared.") });
        KnowledgeService service = service(3, 0.45);
        KnowledgeStatus original = service.ensureIndexed();
        pauseEmbeddings();
        CompletableFuture<KnowledgeStatus> rebuild = asynchronously(service::index);

        try {
            assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
            assertSame(original, service.ensureIndexed());
            assertEquals(List.of("old.txt"), sources(service.search("ALPHA")));
            assertEquals(2, embeddings.documentCalls.get());
        } finally {
            embeddings.resume.countDown();
        }

        KnowledgeStatus replacement = rebuild.get(5, TimeUnit.SECONDS);
        assertNotSame(original, replacement);
        assertSame(replacement, service.ensureIndexed());
        assertEquals(List.of("new.txt"), sources(service.search("ALPHA")));
        assertEquals(2, embeddings.documentCalls.get());
        verify(resources, times(2)).getResources(RESOURCE_PATTERN);
    }

    @Test
    void interruptingAWaitingQuestionDoesNotCancelTheSharedBuild() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is a note shared with a question that stops waiting.")
        });
        KnowledgeService service = service(3, 0.45);
        pauseEmbeddings();
        CompletableFuture<KnowledgeStatus> owner = asynchronously(service::ensureIndexed);
        assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Throwable> interrupted = new CompletableFuture<>();
        AtomicBoolean interruptFlag = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                service.ensureIndexed();
                interrupted.complete(null);
            } catch (Throwable exception) {
                interruptFlag.set(Thread.currentThread().isInterrupted());
                interrupted.complete(exception);
            }
        });
        waiter.start();

        try {
            awaitWaiting(waiter);
            waiter.interrupt();
            assertTrue(interrupted.get(5, TimeUnit.SECONDS) instanceof IOException);
            assertTrue(interruptFlag.get());
            assertFalse(owner.isDone());
        } finally {
            embeddings.resume.countDown();
        }
        assertTrue(owner.get(5, TimeUnit.SECONDS).indexed());
        waiter.join(5_000);
        assertEquals(1, embeddings.documentCalls.get());
    }

    @Test
    void indexesChunksAndPreservesSourceMetadata() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("alpha.txt", "ALPHA explains how to retrieve focused learning notes by meaning. ".repeat(100))
        });
        KnowledgeService service = service(20, 0.9);

        KnowledgeStatus status = service.index();
        List<Document> matches = service.search("ALPHA");

        assertTrue(status.indexed());
        assertEquals(1, status.documentCount());
        assertTrue(status.chunkCount() > 1);
        assertNotNull(status.indexedAt());
        assertEquals(status.chunkCount(), embeddings.documentCalls.get());
        assertEquals(status.chunkCount(), matches.size());
        matches.forEach(document -> {
            assertEquals("alpha.txt", document.getMetadata().get("source"));
            assertEquals("spring-ai-learning", document.getMetadata().get("category"));
            assertEquals("rag", document.getMetadata().get("topic"));
            assertNotNull(document.getText());
        });
    }

    @Test
    void similaritySearchHonorsTopKAndThreshold() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("alpha.txt", "ALPHA is the exact matching note used for this retrieval example."),
                note("beta.txt", "BETA is a nearby matching note used for this retrieval example."),
                note("weather.txt", "WEATHER is unrelated to the topic in this retrieval example.")
        });
        KnowledgeService topOne = service(1, 0.7);
        topOne.index();
        assertEquals(List.of("alpha.txt"), sources(topOne.search("ALPHA")));

        KnowledgeService nearby = service(3, 0.7);
        nearby.index();
        assertEquals(List.of("alpha.txt", "beta.txt"), sources(nearby.search("ALPHA")));

        KnowledgeService strict = service(3, 0.95);
        strict.index();
        assertEquals(List.of("alpha.txt"), sources(strict.search("ALPHA")));
        assertTrue(strict.search("UNRELATED").isEmpty());
    }

    @Test
    void filtersIdenticallyRelevantDocumentsByTopicWithOnlyOneQueryEmbedding() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("tool-calling.txt", "ALPHA explains the Java tool calling loop in this application."),
                note("spring-ai-app.txt", "ALPHA explains the Spring AI application APIs and configuration."),
                note("rag-foundations.txt", "ALPHA explains the retrieval and generation foundations in this application.")
        });
        KnowledgeService service = service(3, 0.95);
        service.index();

        for (RagTopic topic : List.of(RagTopic.RAG, RagTopic.SPRING_AI, RagTopic.TOOLS)) {
            int previousQueries = embeddings.queryCalls.get();
            KnowledgeService.Retrieval retrieval = service.retrieval(topic);
            assertEquals(previousQueries, embeddings.queryCalls.get(), "Capturing an index does not embed a query.");

            List<Document> matches = search(retrieval, "ALPHA");

            assertEquals(previousQueries + 1, embeddings.queryCalls.get());
            assertEquals(1, matches.size());
            Document match = matches.getFirst();
            assertEquals(topic.value(), match.getMetadata().get("topic"));
            assertEquals("spring-ai-learning", match.getMetadata().get("category"));
            assertEquals(switch (topic) {
                case RAG -> "rag-foundations.txt";
                case SPRING_AI -> "spring-ai-app.txt";
                case TOOLS -> "tool-calling.txt";
                default -> throw new AssertionError("Expected one topic.");
            }, match.getMetadata().get("source"));
        }

        KnowledgeService.Retrieval all = service.retrieval(RagTopic.ALL);
        assertNull(all.searchRequest().getFilterExpression());
        assertEquals(List.of("rag-foundations.txt", "spring-ai-app.txt", "tool-calling.txt"),
                sources(search(all, "ALPHA")).stream().sorted().toList());
    }

    @Test
    void topicFilteringStillHonorsTopKAndSimilarityThreshold() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("rag-foundations.txt", "ALPHA is an exact matching note about retrieval foundations."),
                note("indexing-and-advanced-rag.txt", "BETA is a nearby note about document indexing and retrieval."),
                note("embeddings-and-search.txt", "WEATHER is an unrelated note with weak semantic similarity."),
                note("tool-calling.txt", "ALPHA is an equally matching note in a different topic collection.")
        });
        KnowledgeService topOne = service(1, 0.7);
        topOne.index();
        assertEquals(List.of("rag-foundations.txt"), sources(search(topOne.retrieval(RagTopic.RAG), "ALPHA")));

        KnowledgeService nearby = service(3, 0.7);
        nearby.index();
        assertEquals(List.of("rag-foundations.txt", "indexing-and-advanced-rag.txt"),
                sources(search(nearby.retrieval(RagTopic.RAG), "ALPHA")));

        KnowledgeService strict = service(3, 0.95);
        strict.index();
        assertEquals(List.of("rag-foundations.txt"), sources(search(strict.retrieval(RagTopic.RAG), "ALPHA")));
        assertTrue(search(strict.retrieval(RagTopic.RAG), "UNRELATED").isEmpty());
    }

    @Test
    void capturedRetrievalKeepsItsPublishedSnapshotAcrossAReindex() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN))
                .thenReturn(new Resource[] { note("old.txt", "ALPHA is the original published retrieval snapshot.") })
                .thenReturn(new Resource[] { note("new.txt", "ALPHA is the replacement published retrieval snapshot.") });
        KnowledgeService service = service(3, 0.95);
        service.index();
        KnowledgeService.Retrieval original = service.retrieval(RagTopic.RAG);

        service.index();
        KnowledgeService.Retrieval replacement = service.retrieval(RagTopic.RAG);

        assertNotSame(original.vectorStore(), replacement.vectorStore());
        assertEquals(0, embeddings.queryCalls.get());
        assertEquals(List.of("old.txt"), sources(search(original, "ALPHA")));
        assertEquals(List.of("new.txt"), sources(search(replacement, "ALPHA")));
        assertEquals(2, embeddings.queryCalls.get());
    }

    @Test
    void reindexReplacesOldDocumentsWithoutAccumulatingChunks() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN))
                .thenReturn(new Resource[] { note("old.txt", "ALPHA is the original note before a reindex operation.") })
                .thenReturn(new Resource[] { note("new.txt", "BETA is the replacement note after a reindex operation.") });
        KnowledgeService service = service(20, 0);

        service.index();
        KnowledgeStatus replacement = service.index();

        assertEquals(1, replacement.documentCount());
        assertEquals(1, replacement.chunkCount());
        assertEquals(List.of("new.txt"), sources(service.search("ALPHA")));
    }

    @Test
    void failedReindexKeepsLastSnapshotAndAllowsTheNextAttempt() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN))
                .thenReturn(new Resource[] { note("old.txt", "ALPHA is the original working note in the learning index.") })
                .thenReturn(new Resource[] { note("new.txt", "BETA is the replacement note for the next index attempt.") });
        KnowledgeService service = service(20, 0);
        KnowledgeStatus original = service.index();

        embeddings.failDocuments = true;
        assertThrows(IllegalStateException.class, service::index);
        assertSame(original, service.status());
        assertEquals(List.of("old.txt"), sources(service.search("ALPHA")));

        embeddings.failDocuments = false;
        KnowledgeStatus recovered = service.index();
        assertEquals(1, recovered.chunkCount());
        assertEquals(List.of("new.txt"), sources(service.search("ALPHA")));
    }

    @Test
    void rejectsOverlappingIndexRequestsAndReleasesTheGuardAfterCompletion() throws Exception {
        when(resources.getResources(RESOURCE_PATTERN)).thenReturn(new Resource[] {
                note("alpha.txt", "ALPHA is a note whose embedding pauses while another index request arrives.")
        });
        KnowledgeService service = service(3, 0.45);
        embeddings.entered = new CountDownLatch(1);
        embeddings.resume = new CountDownLatch(1);
        CompletableFuture<KnowledgeStatus> first = CompletableFuture.supplyAsync(() -> {
            try {
                return service.index();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });

        try {
            assertTrue(embeddings.entered.await(5, TimeUnit.SECONDS));
            ResponseStatusException conflict = assertThrows(ResponseStatusException.class, service::index);
            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        } finally {
            embeddings.resume.countDown();
        }
        assertTrue(first.get(5, TimeUnit.SECONDS).indexed());
        assertTrue(service.index().indexed());
    }

    @Test
    void missingDocumentsDoNotPublishAnIndexAndCanBeRetried() throws IOException {
        when(resources.getResources(RESOURCE_PATTERN))
                .thenReturn(new Resource[0])
                .thenReturn(new Resource[] { note("alpha.txt", "ALPHA is a note found after correcting the resources.") });
        KnowledgeService service = service(3, 0.45);

        assertThrows(IllegalStateException.class, service::index);
        assertFalse(service.status().indexed());
        assertTrue(service.index().indexed());
    }

    @Test
    void rejectsInvalidSearchConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> service(0, 0.45));
        assertThrows(IllegalArgumentException.class, () -> service(21, 0.45));
        assertThrows(IllegalArgumentException.class, () -> service(3, -0.1));
        assertThrows(IllegalArgumentException.class, () -> service(3, 1.1));
        assertThrows(IllegalArgumentException.class, () -> service(3, Double.NaN));
    }

    private KnowledgeService service(int topK, double threshold) {
        return new KnowledgeService(embeddings, resources, "test-embeddings", topK, threshold);
    }

    private void pauseEmbeddings() {
        embeddings.entered = new CountDownLatch(1);
        embeddings.resume = new CountDownLatch(1);
    }

    private static CompletableFuture<KnowledgeStatus> asynchronously(IndexOperation operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private static Thread startWaiter(KnowledgeService service, CompletableFuture<KnowledgeStatus> result) {
        Thread waiter = new Thread(() -> {
            try {
                result.complete(service.ensureIndexed());
            } catch (Throwable exception) {
                result.completeExceptionally(exception);
            }
        });
        waiter.start();
        return waiter;
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "The second question should join the active build.");
    }

    @FunctionalInterface
    private interface IndexOperation {
        KnowledgeStatus run() throws IOException;
    }

    private static Resource note(String filename, String text) {
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static List<String> sources(List<Document> documents) {
        return documents.stream().map(document -> (String) document.getMetadata().get("source")).toList();
    }

    private static List<Document> search(KnowledgeService.Retrieval retrieval, String query) {
        return retrieval.vectorStore().similaritySearch(
                SearchRequest.from(retrieval.searchRequest()).query(query).build());
    }

    /** Known vectors exercise the real vector store without a remote model. */
    private static final class DeterministicEmbeddings implements EmbeddingModel {
        private final AtomicInteger documentCalls = new AtomicInteger();
        private final AtomicInteger queryCalls = new AtomicInteger();
        private volatile boolean failDocuments;
        private CountDownLatch entered;
        private CountDownLatch resume;

        @Override
        public float[] embed(Document document) {
            documentCalls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to resume test embeddings.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            if (failDocuments) {
                throw new IllegalStateException("Embedding provider unavailable.");
            }
            return vector(document.getText());
        }

        @Override
        public float[] embed(String text) {
            queryCalls.incrementAndGet();
            return vector(text);
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("The test uses explicit document and query embeddings.");
        }

        private float[] vector(String text) {
            if (text.contains("ALPHA")) return new float[] { 1, 0 };
            if (text.contains("BETA")) return new float[] { 0.8f, 0.6f };
            if (text.contains("WEATHER")) return new float[] { 0, 1 };
            return new float[] { -1, 0 };
        }
    }
}
