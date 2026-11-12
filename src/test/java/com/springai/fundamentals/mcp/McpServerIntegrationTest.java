package com.springai.fundamentals.mcp;

import com.springai.fundamentals.SpringAiFundamentalsApplication;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises real MCP HTTP requests and the remote tool loop without a live model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "app.tools.time-zone=UTC"
})
@ActiveProfiles("mcp-server")
class McpServerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    private McpAsyncClient client;
    private McpSchema.InitializeResult initialization;

    @BeforeEach
    void connect() {
        WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport
                .builder(WebClient.builder().baseUrl("http://127.0.0.1:" + port))
                .endpoint("/mcp")
                .build();
        client = McpClient.async(transport)
                .requestTimeout(TIMEOUT)
                .clientInfo(McpSchema.Implementation.builder("learning-mcp-test", "1.0.0").build())
                .build();
        initialization = client.initialize().block(TIMEOUT);
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully().block(TIMEOUT);
        }
    }

    @Test
    void negotiatesTheProtocolAndDiscoversOnlyTheExplicitReadOnlyTools() {
        assertNotNull(initialization);
        assertEquals("2025-11-25", initialization.protocolVersion());
        assertNotNull(initialization.capabilities().tools());
        assertNotNull(initialization.capabilities().resources());
        assertNotNull(initialization.capabilities().prompts());

        List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT).tools();
        assertEquals(List.of("learning_outline", "learning_server_time"),
                tools.stream().map(McpSchema.Tool::name).sorted().toList());
        tools.forEach(tool -> {
            assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint());
            assertEquals(Boolean.FALSE, tool.annotations().openWorldHint());
            assertFalse(tool.inputSchema().toString().contains("timeZone"));
        });
        McpSchema.Tool outline = tools.stream().filter(tool -> tool.name().equals("learning_outline"))
                .findFirst().orElseThrow();
        assertTrue(outline.inputSchema().toString().contains("topic"));
    }

    @Test
    void callsToolsAcrossHttpAndReportsInvalidTopicsAsToolErrors() {
        McpSchema.CallToolResult outline = call("learning_outline", Map.of("topic", "rag"));
        assertFalse(Boolean.TRUE.equals(outline.isError()));
        assertTrue(text(outline).contains("Retrieval augmented generation: embeddings"));

        McpSchema.CallToolResult time = call("learning_server_time", Map.of());
        assertFalse(Boolean.TRUE.equals(time.isError()));
        String renderedTime = text(time);
        // The annotation's default result converter serializes String values as JSON.
        if (renderedTime.startsWith("\"")) {
            renderedTime = renderedTime.substring(1, renderedTime.length() - 1);
        }
        assertEquals(ZoneId.of("UTC"), ZonedDateTime.parse(renderedTime).getZone());

        McpSchema.CallToolResult invalid = call("learning_outline", Map.of("topic", "../../etc/passwd"));
        assertEquals(Boolean.TRUE, invalid.isError());
        assertTrue(text(invalid).contains("Topic must be one of"));
    }

    @Test
    void discoversAndReadsAResourceTemplateAndGetsAUserSelectedPrompt() {
        assertTrue(client.listResourceTemplates().block(TIMEOUT).resourceTemplates().stream()
                .anyMatch(resource -> resource.uriTemplate().equals("learning://topics/{topic}")));
        McpSchema.ReadResourceResult resource = client.readResource(
                McpSchema.ReadResourceRequest.builder("learning://topics/rag").build()).block(TIMEOUT);
        McpSchema.TextResourceContents contents = assertInstanceOf(McpSchema.TextResourceContents.class,
                resource.contents().getFirst());
        assertTrue(contents.text().contains("Retrieval augmented generation"));
        assertEquals("text/plain", contents.mimeType());

        assertEquals(List.of("study_topic"), client.listPrompts().block(TIMEOUT).prompts().stream()
                .map(McpSchema.Prompt::name).toList());
        McpSchema.GetPromptResult prompt = client.getPrompt(McpSchema.GetPromptRequest.builder("study_topic")
                .arguments(Map.of("topic", "tools")).build()).block(TIMEOUT);
        assertEquals(1, prompt.messages().size());
        assertEquals(McpSchema.Role.USER, prompt.messages().getFirst().role());
        assertTrue(assertInstanceOf(McpSchema.TextContent.class, prompt.messages().getFirst().content())
                .text().contains("Tool calling:"));
    }

    @Test
    void returnsTheRealRemoteToolResultToTheModelBeforeGeneratingTheAnswer() {
        AsyncMcpToolCallbackProvider provider = AsyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .toolFilter((connection, tool) -> tool.name().equals("learning_outline"))
                .toolNamePrefixGenerator((connection, tool) -> tool.name())
                .build();
        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertEquals(1, callbacks.length);

        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage toolCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("remote-outline", "function", "learning_outline",
                        "{\"topic\":\"rag\"}")))
                .build();
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(toolCall)))))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("Study embeddings and vector stores."))))));

        String answer = ChatClient.builder(model).build().prompt()
                .user("What does the RAG lesson cover?")
                .tools((Object[]) callbacks)
                .stream().content().collectList().map(parts -> String.join("", parts)).block(TIMEOUT);

        assertEquals("Study embeddings and vector stores.", answer);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(prompts.capture());
        ToolResponseMessage toolResponse = prompts.getAllValues().get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .findFirst().orElseThrow();
        assertEquals("remote-outline", toolResponse.getResponses().getFirst().id());
        assertEquals("learning_outline", toolResponse.getResponses().getFirst().name());
        assertTrue(toolResponse.getResponses().getFirst().responseData()
                .contains("Retrieval augmented generation: embeddings"));
    }

    @Test
    void connectsTheClientProfileToTheServerThroughSpringAutoConfiguration() {
        try (ConfigurableApplicationContext host = new SpringApplicationBuilder(SpringAiFundamentalsApplication.class)
                .profiles("mcp-client")
                .initializers(context -> ((GenericApplicationContext) context).registerBean(
                        "reactorResourceFactory", ReactorResourceFactory.class, () -> {
                            // Closing this second app must not dispose the test server's Netty resources.
                            ReactorResourceFactory resources = new ReactorResourceFactory();
                            resources.setUseGlobalResources(false);
                            return resources;
                        }))
                .run("--server.port=0", "--management.server.port=0",
                        "--spring.ai.mcp.client.streamable-http.connections.lessons.url=http://127.0.0.1:" + port,
                        "--spring.ai.ollama.base-url=http://127.0.0.1:1", "--spring.ai.mcp.server.enabled=false")) {
            assertEquals(List.of("mcp-client"), List.of(host.getEnvironment().getActiveProfiles()));
            McpRemoteTools remoteTools = host.getBean(McpRemoteTools.class);
            assertTrue(remoteTools.isEnabled());
            assertEquals(List.of("learning_outline", "learning_server_time"), remoteTools.getToolNames());
            ToolCallback outline = List.of(remoteTools.getToolCallbacks()).stream()
                    .filter(callback -> callback.getToolDefinition().name().equals("learning_outline"))
                    .findFirst().orElseThrow();
            assertTrue(outline.call("{\"topic\":\"mcp\"}").contains("Model Context Protocol"));

            int hostPort = host.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + hostPort).build()
                    .get().uri("/api/mcp").exchange()
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.enabled").isEqualTo(true)
                    .jsonPath("$.tools[0]").isEqualTo("learning_outline")
                    .jsonPath("$.tools[1]").isEqualTo("learning_server_time");
        }
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build()).block(TIMEOUT);
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream().map(content -> assertInstanceOf(McpSchema.TextContent.class, content).text())
                .reduce("", String::concat);
    }
}
