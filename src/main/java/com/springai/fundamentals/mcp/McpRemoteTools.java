package com.springai.fundamentals.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Keeps remote tool discovery and registration inside the explicit MCP chat mode. */
@Component
public class McpRemoteTools {

    static final Set<String> ALLOWED_TOOLS = Set.of("learning_outline", "learning_server_time");

    private static final String SETUP_HINT = "Start the MCP server and restart this app with the mcp-client profile.";

    private final ObjectProvider<AsyncMcpToolCallbackProvider> providers;
    @Getter
    private final boolean enabled;

    public McpRemoteTools(ObjectProvider<AsyncMcpToolCallbackProvider> providers,
            @Value("${spring.ai.mcp.client.enabled:false}") boolean enabled) {
        this.providers = providers;
        this.enabled = enabled;
    }

    public List<String> getToolNames() {
        return enabled ? Arrays.stream(getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).sorted().toList() : List.of();
    }

    public ToolCallback[] getToolCallbacks() {
        if (!enabled) {
            throw unavailable("MCP tools are disabled. " + SETUP_HINT);
        }
        ToolCallback[] callbacks;
        try {
            AsyncMcpToolCallbackProvider provider = providers.getIfAvailable();
            callbacks = provider == null ? new ToolCallback[0]
                    : Arrays.stream(provider.getToolCallbacks())
                            .filter(callback -> ALLOWED_TOOLS.contains(callback.getToolDefinition().name()))
                            .toArray(ToolCallback[]::new);
        } catch (RuntimeException exception) {
            throw unavailable("Could not discover the MCP lesson tools. " + SETUP_HINT);
        }
        if (callbacks.length == 0) {
            throw unavailable("No approved MCP lesson tools are available. " + SETUP_HINT);
        }
        return callbacks;
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
