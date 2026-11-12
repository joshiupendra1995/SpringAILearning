package com.springai.fundamentals.mcp;

import com.springai.fundamentals.tool.LearningTools;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Public, read-only MCP capabilities published only by the dedicated server profile. */
@Component
@Profile("mcp-server")
public class LearningMcpCapabilities {

    private final LearningTools learningTools;
    private final String timeZone;

    public LearningMcpCapabilities(LearningTools learningTools,
            @Value("${app.tools.time-zone}") String timeZone) {
        this.learningTools = learningTools;
        this.timeZone = timeZone;
    }

    @McpTool(name = "learning_outline", description = "Read the outline of a topic in the public learning catalog. "
            + "Topics: fundamentals, spring-ai, advisors, rag, tools, mcp.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    openWorldHint = false))
    public Mono<String> lessonOutline(
            @McpToolParam(description = "Topic: fundamentals, spring-ai, advisors, rag, tools, mcp", required = true)
            String topic) {
        return Mono.fromSupplier(() -> learningTools.getLessonOutline(topic));
    }

    @McpTool(name = "learning_server_time", description = "Read the current date and time in the MCP server's "
            + "configured timezone. The timezone is controlled by the server.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    openWorldHint = false))
    public Mono<String> serverTime() {
        return Mono.fromSupplier(() -> learningTools.getCurrentDateTime(
                new ToolContext(Map.of("timeZone", timeZone))));
    }

    @McpResource(uri = "learning://topics/{topic}", name = "Lesson outline", mimeType = "text/plain",
            description = "Public lesson outline. Topics: fundamentals, spring-ai, advisors, rag, tools, mcp.")
    public Mono<String> topicResource(String topic) {
        return Mono.fromSupplier(() -> learningTools.getLessonOutline(topic));
    }

    @McpPrompt(name = "study_topic", description = "Create a study request for a public learning topic.")
    public Mono<McpSchema.PromptMessage> studyTopic(
            @McpArg(name = "topic", description = "Topic: fundamentals, spring-ai, advisors, rag, tools, mcp",
                    required = true) String topic) {
        return Mono.fromSupplier(() -> {
            String outline = learningTools.getLessonOutline(topic);
            return McpSchema.PromptMessage.builder(McpSchema.Role.USER,
                    McpSchema.TextContent.builder("Teach me this topic using the following lesson outline. "
                            + "Explain the main ideas, give a small Java example, and finish with a practice question.\n\n"
                            + outline).build()).build();
        });
    }
}
