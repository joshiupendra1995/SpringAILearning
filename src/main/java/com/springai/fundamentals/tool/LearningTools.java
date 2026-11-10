package com.springai.fundamentals.tool;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Read-only application code that the model can request in tool mode. */
@Component
public class LearningTools {

    private final Clock clock;

    public LearningTools() {
        this(Clock.systemUTC());
    }

    LearningTools(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Tool(description = "Get the current date and time in the application's configured timezone. "
            + "Use this when a question requires today's date or the current time; do not guess from training data.")
    public String getCurrentDateTime(ToolContext toolContext) {
        // ToolContext is supplied by application code and excluded from the model's input schema.
        if (toolContext == null
                || !(toolContext.getContext().get("timeZone") instanceof String timeZone)
                || timeZone.isBlank()) {
            throw new IllegalArgumentException("A server-configured timeZone is required.");
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("The server-configured timeZone is invalid.", exception);
        }
        return ZonedDateTime.now(clock.withZone(zone)).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    @Tool(description = "Get the outline of a topic taught in this learning application. "
            + "Use this to identify what to study in fundamentals, spring-ai, advisors, rag, or tools. "
            + "This reads the application's lesson catalog and does not change anything.")
    public String getLessonOutline(
            @ToolParam(description = "Topic: fundamentals, spring-ai, advisors, rag, or tools") String topic) {
        if (topic == null) {
            throw invalidTopic();
        }
        return switch (topic.trim().toLowerCase(Locale.ROOT)) {
            case "fundamentals" -> "AI fundamentals: artificial intelligence, machine learning, deep learning, "
                    + "generative AI, tokens, context windows, model parameters, hallucinations, and training cutoffs.";
            case "spring-ai" -> "Spring AI: ChatModel and ChatClient, provider starters and BOM, prompts and message "
                    + "roles, PromptTemplate, model options, response metadata, streaming, multimodal input, "
                    + "structured output, and testing AI applications with mocked models and evaluators.";
            case "advisors" -> "Advisors: request and response interception, ordering, shared context, bounded "
                    + "session chat memory, conversation IDs, and bounded validation retries.";
            case "rag" -> "Retrieval augmented generation: embeddings, matching model and dimensions, vector "
                    + "stores, top K, similarity thresholds, metadata filtering, extract-transform-load indexing, "
                    + "chunking, EmbeddingModel, Document, VectorStore, QuestionAnswerAdvisor, custom prompt "
                    + "templates, grounded generation, and modular RetrievalAugmentationAdvisor examples.";
            case "tools" -> "Tool calling: tool descriptions and input schemas, the model-request/application-execute "
                    + "loop, read-only retrieval versus actions, argument validation, and trusted ToolContext inputs.";
            default -> throw invalidTopic();
        };
    }

    private IllegalArgumentException invalidTopic() {
        return new IllegalArgumentException("Topic must be one of: fundamentals, spring-ai, advisors, rag, tools.");
    }
}
