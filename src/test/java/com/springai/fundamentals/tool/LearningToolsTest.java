package com.springai.fundamentals.tool;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningToolsTest {

    private final LearningTools tools = new LearningTools(
            Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void usesTheServerSuppliedTimeZone() {
        String result = tools.getCurrentDateTime(new ToolContext(Map.of("timeZone", "Asia/Kolkata")));

        assertEquals("2026-11-09T13:30:00+05:30[Asia/Kolkata]", result);
    }

    @Test
    void requiresAValidServerTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> tools.getCurrentDateTime(null));
        assertThrows(IllegalArgumentException.class, () -> tools.getCurrentDateTime(new ToolContext(Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> tools.getCurrentDateTime(new ToolContext(Map.of("timeZone", 123))));
        assertThrows(IllegalArgumentException.class,
                () -> tools.getCurrentDateTime(new ToolContext(Map.of("timeZone", " "))));
        assertThrows(IllegalArgumentException.class,
                () -> tools.getCurrentDateTime(new ToolContext(Map.of("timeZone", "invalid-zone"))));
    }

    @Test
    void looksUpOnlySupportedLessonTopics() {
        assertTrue(tools.getLessonOutline(" fundamentals ").contains("tokens"));
        assertTrue(tools.getLessonOutline("SPRING-AI").contains("ChatClient"));
        assertTrue(tools.getLessonOutline("spring-ai").contains("testing AI applications"));
        assertTrue(tools.getLessonOutline("advisors").contains("conversation IDs"));
        assertTrue(tools.getLessonOutline("rag").contains("embeddings"));
        assertTrue(tools.getLessonOutline("tools").contains("ToolContext"));
        assertTrue(tools.getLessonOutline("mcp").contains("Streamable HTTP"));

        assertThrows(IllegalArgumentException.class, () -> tools.getLessonOutline(null));
        assertThrows(IllegalArgumentException.class, () -> tools.getLessonOutline(""));
        assertThrows(IllegalArgumentException.class, () -> tools.getLessonOutline("arbitrary-private-resource"));
    }

    @Test
    void excludesTrustedContextFromTheModelSchemaAndInjectsItAtExecution() {
        var callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("getCurrentDateTime"))
                .findFirst()
                .orElseThrow();
        String schema = callback.getToolDefinition().inputSchema();

        assertFalse(schema.contains("timeZone"));
        assertFalse(schema.contains("toolContext"));
        assertTrue(callback.call("{}", new ToolContext(Map.of("timeZone", "Asia/Kolkata")))
                .contains("2026-11-09T13:30:00+05:30[Asia/Kolkata]"));
    }
}
