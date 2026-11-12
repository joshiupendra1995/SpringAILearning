package com.springai.fundamentals.mcp;

import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mcp-client")
public class McpClientConfiguration {

    /** Filter the remote catalog before Spring AI advertises tool schemas to the model. */
    @Bean
    McpToolFilter learningMcpToolFilter() {
        return (connection, tool) -> McpRemoteTools.ALLOWED_TOOLS.contains(tool.name());
    }
}
