package com.springai.fundamentals.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpRemoteToolsTest {

    private AsyncMcpToolCallbackProvider callbackProvider;
    private StaticListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        callbackProvider = mock(AsyncMcpToolCallbackProvider.class);
        beans = new StaticListableBeanFactory();
        beans.addBean("mcpAsyncToolCallbacks", callbackProvider);
    }

    @Test
    void disabledModeDoesNotDiscoverRemoteToolsEvenForTheStatusEndpoint() {
        McpRemoteTools tools = tools(false);

        assertFalse(tools.isEnabled());
        assertEquals(List.of(), tools.getToolNames());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, tools::getToolCallbacks);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertTrue(error.getReason().contains("mcp-client profile"));
        verify(callbackProvider, never()).getToolCallbacks();
    }

    @Test
    void exposesOnlyApprovedRemoteToolsAndReportsTheirNames() {
        ToolCallback outline = callback("learning_outline");
        ToolCallback time = callback("learning_server_time");
        ToolCallback unapproved = callback("delete_ticket");
        ToolCallback renamed = callback("alt_1_learning_outline");
        when(callbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[] {
                time, unapproved, outline, renamed });
        McpRemoteTools tools = tools(true);

        assertTrue(tools.isEnabled());
        assertArrayEquals(new ToolCallback[] { time, outline }, tools.getToolCallbacks());
        assertEquals(List.of("learning_outline", "learning_server_time"), tools.getToolNames());
    }

    @Test
    void unavailableRemoteDiscoveryReturnsASafeSetupMessage() {
        when(callbackProvider.getToolCallbacks()).thenThrow(new IllegalStateException("private server details"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, tools(true)::getToolCallbacks);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertTrue(error.getReason().contains("Start the MCP server"));
        assertFalse(error.getReason().contains("private server details"));
    }

    @Test
    void refusesACatalogWithoutApprovedTools() {
        ToolCallback unapproved = callback("delete_ticket");
        when(callbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[] { unapproved });

        ResponseStatusException error = assertThrows(ResponseStatusException.class, tools(true)::getToolCallbacks);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertTrue(error.getReason().contains("No approved MCP lesson tools"));
    }

    @Test
    void enabledClientWithoutAProviderReturnsASetupMessage() {
        McpRemoteTools tools = new McpRemoteTools(new StaticListableBeanFactory()
                .getBeanProvider(AsyncMcpToolCallbackProvider.class), true);

        assertThrows(ResponseStatusException.class, tools::getToolCallbacks);
    }

    @Test
    void filtersTheRawCatalogBeforeSpringAiBuildsCallbacks() {
        var filter = new McpClientConfiguration().learningMcpToolFilter();
        McpSchema.Tool outline = mock(McpSchema.Tool.class);
        McpSchema.Tool unexpected = mock(McpSchema.Tool.class);
        when(outline.name()).thenReturn("learning_outline");
        when(unexpected.name()).thenReturn("delete_ticket");

        assertTrue(filter.test(null, outline));
        assertFalse(filter.test(null, unexpected));
    }

    private McpRemoteTools tools(boolean enabled) {
        return new McpRemoteTools(beans.getBeanProvider(AsyncMcpToolCallbackProvider.class), enabled);
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("Read public learning information.").inputSchema("{}").build());
        return callback;
    }
}
