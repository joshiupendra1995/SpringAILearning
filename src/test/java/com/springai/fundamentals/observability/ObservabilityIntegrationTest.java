package com.springai.fundamentals.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses an in-process HTTP provider so real observations are tested without Ollama or a collector. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "management.tracing.sampling.probability=1.0",
        "spring.ai.ollama.chat.model=observability-chat",
        "spring.ai.ollama.embedding.model=observability-embedding",
        "app.ollama.response-timeout=10s"
})
@Import(ObservabilityIntegrationTest.TraceCaptureConfiguration.class)
class ObservabilityIntegrationTest {

    private static final FakeOllama PROVIDER = new FakeOllama();
    private static final String TRACE_ID = "11111111111111111111111111111111";
    private static final String PRIVATE_QUESTION = "PRIVATE-QUESTION-DO-NOT-TAG";

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private RecordingSpanExporter spans;

    private WebTestClient application;
    private WebTestClient management;

    @DynamicPropertySource
    static void useLocalProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", PROVIDER::baseUrl);
    }

    @BeforeEach
    void clients() {
        application = client(applicationPort);
        management = client(managementPort);
        PROVIDER.requests.clear();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.server.stop(0);
    }

    @Test
    void exposesOnlyTheConfiguredManagementEndpointsOnTheManagementPort() {
        assertTrue(applicationPort != managementPort);
        management.get().uri("/actuator/health").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        management.get().uri("/actuator/metrics").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.names").isArray();
        management.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk().expectBody(String.class)
                .value(body -> assertTrue(body.contains("jvm_memory_used_bytes")));
        management.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        management.get().uri("/actuator/beans").exchange().expectStatus().isNotFound();
        application.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
    }

    @Test
    void recordsRagOperationsAndTokenUsageAndPropagatesTheIncomingTrace() {
        application.post().uri("/api/chat")
                .header("traceparent", "00-" + TRACE_ID + "-2222222222222222-01")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"message\":\"How does retrieval work? " + PRIVATE_QUESTION + "\",\"mode\":\"rag\"}")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains("A grounded answer from the learning notes."));
                    assertTrue(body.contains("Retrieved sources:"));
                    assertTrue(body.contains("event:done"));
                });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertTimerRecorded("spring.ai.chat.client");
            assertTimerRecorded("spring.ai.advisor");
            assertTimerRecorded("gen_ai.client.operation");
            assertTimerRecorded("db.vector.client.operation");
            assertTokenCount("input", 30);
            assertTokenCount("output", 12);
            assertTokenCount("total", 42);
        });

        List<ProviderRequest> requests = List.copyOf(PROVIDER.requests);
        assertTrue(requests.stream().anyMatch(request -> request.path().equals("/api/embed")));
        assertTrue(requests.stream().anyMatch(request -> request.path().equals("/api/chat")));
        for (String path : List.of("/api/embed", "/api/chat")) {
            assertTrue(requests.stream().filter(request -> request.path().equals(path))
                    .anyMatch(request -> request.traceparent() != null
                            && request.traceparent().startsWith("00-" + TRACE_ID + "-")),
                    () -> "The incoming trace was not propagated to " + path + ": " + requests.stream()
                            .map(request -> request.path() + "=" + request.traceparent()).toList());
        }
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        List<SpanData> requestSpans = spans.finished.stream()
                .filter(span -> span.getTraceId().equals(TRACE_ID)).toList();
        assertTrue(requestSpans.size() >= 5, "The request did not produce its nested AI and HTTP spans.");
        assertTrue(requestSpans.stream().anyMatch(span -> "chat_client".equals(
                span.getAttributes().get(AttributeKey.stringKey("spring.ai.kind")))));
        assertTrue(requestSpans.stream().anyMatch(span -> "advisor".equals(
                span.getAttributes().get(AttributeKey.stringKey("spring.ai.kind")))));
        assertTrue(requestSpans.stream().anyMatch(span -> "chat".equals(
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))));
        assertTrue(requestSpans.stream().allMatch(span -> !span.getParentSpanId().equals("0000000000000000")));
        assertNoSensitiveMeterTags();
        management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertTrue(body.contains("gen_ai_client_token_usage_total"));
                    assertTrue(body.contains("spring_ai_chat_client_seconds_count"));
                    assertFalse(body.contains(PRIVATE_QUESTION));
                });
    }

    @Test
    void recordsAnActualToolExecution() {
        application.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{\"message\":\"Look up the RAG lesson outline\",\"mode\":\"tools\"}")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertTrue(body.contains("The requested lesson outline was read.")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            double count = meters.find("spring.ai.tool").tag("spring.ai.tool.definition.name", "getLessonOutline")
                    .timers().stream().mapToDouble(timer -> timer.count()).sum();
            assertTrue(count >= 1, "The Java tool executed without recording its Spring AI observation.");
        });
        assertTrue(PROVIDER.requests.stream().anyMatch(request -> request.body().contains("\"role\":\"tool\"")));
        assertNoSensitiveMeterTags();
    }

    @Test
    void keepsContentLoggingDisabledByDefault() {
        assertEquals(false, environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class));
        for (String property : List.of(
                "spring.ai.chat.client.observations.log-prompt",
                "spring.ai.chat.client.observations.log-completion",
                "spring.ai.chat.observations.log-prompt",
                "spring.ai.chat.observations.log-completion",
                "spring.ai.chat.observations.include-error-logging",
                "spring.ai.tools.observations.include-content",
                "spring.ai.vectorstore.observations.log-query-response")) {
            assertEquals(false, environment.getProperty(property, Boolean.class), property);
        }
    }

    private void assertTimerRecorded(String name) {
        assertTrue(meters.find(name).timers().stream().anyMatch(timer -> timer.count() > 0),
                () -> "Missing recorded observation: " + name);
    }

    private void assertTokenCount(String type, double minimum) {
        double count = meters.find("gen_ai.client.token.usage")
                .tags("gen_ai.operation.name", "chat", "gen_ai.token.type", type)
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
        assertTrue(count >= minimum, () -> "Missing model-reported " + type + " token count: " + count);
    }

    private void assertNoSensitiveMeterTags() {
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getValue().contains(PRIVATE_QUESTION), meter.getId().toString());
                assertFalse(tag.getValue().contains("A grounded answer"), meter.getId().toString());
                assertFalse(tag.getValue().contains("{\"topic\""), meter.getId().toString());
                assertFalse(tag.getKey().contains("conversation.id"), meter.getId().toString());
            });
        }
    }

    private static WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    private record ProviderRequest(String path, String traceparent, String body) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TraceCaptureConfiguration {
        @Bean
        RecordingSpanExporter recordingSpanExporter() {
            return new RecordingSpanExporter();
        }
    }

    private static final class RecordingSpanExporter implements SpanExporter {
        private final List<SpanData> finished = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            finished.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class FakeOllama {
        private final HttpServer server;
        private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
        private final JsonMapper json = JsonMapper.builder().build();

        private FakeOllama() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/embed", this::embeddings);
                server.createContext("/api/chat", this::chat);
                server.start();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private JsonNode read(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new ProviderRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("traceparent"), body));
            return json.readTree(body);
        }

        private void embeddings(HttpExchange exchange) throws IOException {
            JsonNode request = read(exchange);
            JsonNode inputs = request.get("input");
            int count = inputs.isArray() ? inputs.size() : 1;
            String vectors = String.join(",", java.util.Collections.nCopies(count, "[1.0,0.0]"));
            send(exchange, "application/json", "{\"model\":\"observability-embedding\",\"embeddings\":["
                    + vectors + "],\"total_duration\":1,\"load_duration\":0,\"prompt_eval_count\":4}");
        }

        private void chat(HttpExchange exchange) throws IOException {
            JsonNode request = read(exchange);
            boolean toolsRequested = request.has("tools") && !request.get("tools").isEmpty();
            boolean toolResultPresent = request.get("messages").valueStream()
                    .anyMatch(message -> "tool".equals(message.path("role").asText()));
            String message;
            if (toolsRequested && !toolResultPresent) {
                message = "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"outline-1\","
                        + "\"function\":{\"name\":\"getLessonOutline\",\"arguments\":{\"topic\":\"rag\"}}}]}";
            } else {
                String answer = toolResultPresent ? "The requested lesson outline was read."
                        : "A grounded answer from the learning notes.";
                message = "{\"role\":\"assistant\",\"content\":\"" + answer + "\"}";
            }
            String response = "{\"model\":\"observability-chat\",\"created_at\":\"2026-11-11T00:00:00Z\","
                    + "\"message\":" + message + ",\"done\":true,\"done_reason\":\"stop\","
                    + "\"prompt_eval_count\":30,\"eval_count\":12,\"total_duration\":1}\n";
            send(exchange, "application/x-ndjson", response);
        }

        private void send(HttpExchange exchange, String contentType, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
