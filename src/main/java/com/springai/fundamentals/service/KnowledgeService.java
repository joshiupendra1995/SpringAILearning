package com.springai.fundamentals.service;

import com.springai.fundamentals.model.KnowledgeStatus;
import com.springai.fundamentals.model.RagTopic;
import com.springai.fundamentals.observation.PrivacyPreservingVectorStoreObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final ResourcePatternResolver resourceResolver;
    private final ObservationRegistry observationRegistry;
    private final String embeddingModelName;
    private final int topK;
    private final double similarityThreshold;
    private final Object indexMonitor = new Object();
    private CompletableFuture<KnowledgeStatus> inFlightIndex;
    private volatile IndexSnapshot snapshot;

    @Autowired
    public KnowledgeService(EmbeddingModel embeddingModel, ResourcePatternResolver resourceResolver,
            ObservationRegistry observationRegistry,
            @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}") String embeddingModelName,
            @Value("${app.rag.top-k:3}") int topK,
            @Value("${app.rag.similarity-threshold:0.45}") double similarityThreshold) {
        if (topK < 1 || topK > 20 || similarityThreshold < 0 || similarityThreshold > 1
                || !Double.isFinite(similarityThreshold)) {
            throw new IllegalArgumentException("RAG top-k must be 1–20 and similarity-threshold must be 0–1.");
        }
        this.embeddingModel = embeddingModel;
        this.resourceResolver = resourceResolver;
        this.observationRegistry = Objects.requireNonNull(observationRegistry);
        this.embeddingModelName = embeddingModelName;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public KnowledgeStatus status() {
        IndexSnapshot current = snapshot;
        return current == null
                ? new KnowledgeStatus(false, 0, 0, embeddingModelName, null)
                : current.status();
    }

    // Indexing ETL: extraction -> chunking -> embedding + storage.
    // Build a replacement first so failed reindexing never destroys the last working index.
    public KnowledgeStatus index() throws IOException {
        CompletableFuture<KnowledgeStatus> build;
        synchronized (indexMonitor) {
            if (inFlightIndex != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Learning notes are already being indexed.");
            }
            build = new CompletableFuture<>();
            inFlightIndex = build;
        }
        return buildIndex(build);
    }

    /** Prepares the notes on first use, sharing an existing build with other callers. */
    public KnowledgeStatus ensureIndexed() throws IOException {
        CompletableFuture<KnowledgeStatus> build;
        boolean ownsBuild;
        synchronized (indexMonitor) {
            if (snapshot != null) {
                return snapshot.status();
            }
            ownsBuild = inFlightIndex == null;
            if (ownsBuild) {
                inFlightIndex = new CompletableFuture<>();
            }
            build = inFlightIndex;
        }
        return ownsBuild ? buildIndex(build) : awaitIndex(build);
    }

    private KnowledgeStatus buildIndex(CompletableFuture<KnowledgeStatus> build) throws IOException {
        try {
            Resource[] resources = resourceResolver.getResources("classpath*:knowledge/*.txt");
            Arrays.sort(resources, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            List<Document> documents = new ArrayList<>();
            for (Resource resource : resources) {
                TextReader reader = new TextReader(resource);
                reader.getCustomMetadata().put("source", Objects.requireNonNull(resource.getFilename()));
                reader.getCustomMetadata().put("category", "spring-ai-learning");
                reader.getCustomMetadata().put("topic", topicFor(resource.getFilename()));
                documents.addAll(reader.read());
            }
            if (documents.isEmpty()) {
                throw new IllegalStateException("No bundled learning notes were found.");
            }
            List<Document> chunks = TokenTextSplitter.builder()
                    .withChunkSize(300)
                    .withMinChunkSizeChars(100)
                    .withMinChunkLengthToEmbed(30)
                    .build()
                    .split(documents);
            SimpleVectorStore replacement = SimpleVectorStore.builder(embeddingModel)
                    .observationRegistry(observationRegistry)
                    .customObservationConvention(new PrivacyPreservingVectorStoreObservationConvention())
                    .build();
            replacement.add(chunks);
            KnowledgeStatus status = new KnowledgeStatus(
                    true, documents.size(), chunks.size(), embeddingModelName, Instant.now());
            synchronized (indexMonitor) {
                snapshot = new IndexSnapshot(replacement, status);
                inFlightIndex = null;
                build.complete(status);
            }
            return status;
        } catch (IOException | RuntimeException | Error exception) {
            synchronized (indexMonitor) {
                inFlightIndex = null;
                build.completeExceptionally(exception);
            }
            throw exception;
        }
    }

    private KnowledgeStatus awaitIndex(CompletableFuture<KnowledgeStatus> build) throws IOException {
        try {
            return build.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preparing the learning notes.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not prepare the learning notes.", cause);
        }
    }

    public List<Document> search(String query) {
        Retrieval retrieval = retrieval(RagTopic.ALL);
        return retrieval.vectorStore().similaritySearch(
                SearchRequest.from(retrieval.searchRequest()).query(query).build());
    }

    // Capture one published index for the entire request, including a concurrent rebuild.
    public Retrieval retrieval(RagTopic topic) {
        IndexSnapshot current = snapshot;
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Index the learning notes first using the Index learning notes button.");
        }
        SearchRequest.Builder request = SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        if (topic != RagTopic.ALL) {
            request.filterExpression(new FilterExpressionBuilder().eq("topic", topic.value()).build());
        }
        return new Retrieval(current.store(), request.build());
    }

    private String topicFor(String filename) {
        return switch (filename) {
            case "tool-calling.txt", "mcp.txt" -> "tools";
            case "spring-ai-app.txt", "testing-ai-applications.txt", "observability.txt" -> "spring-ai";
            default -> "rag";
        };
    }

    public record Retrieval(VectorStore vectorStore, SearchRequest searchRequest) {
    }

    private record IndexSnapshot(VectorStore store, KnowledgeStatus status) {
    }
}
