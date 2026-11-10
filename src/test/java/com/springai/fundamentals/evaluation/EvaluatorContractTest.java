package com.springai.fundamentals.evaluation;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests evaluator wiring and parsing, without claiming to judge the quality of a live answer. */
class EvaluatorContractTest {

    private ChatModel judgeModel;

    @BeforeEach
    void setUp() {
        judgeModel = mock(ChatModel.class);
        when(judgeModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    }

    @Test
    void rendersQuestionAnswerAndEvidenceIntoTheCustomRelevancePrompt() {
        when(judgeModel.call(any(Prompt.class))).thenReturn(verdict("  YeS\n"));
        String question = "When is the learning index prepared?";
        String evidence = "The learning index is prepared on the first RAG question.";
        String answer = "The first RAG question prepares the index.";

        var response = EvaluationJudges.relevancy(ChatClient.builder(judgeModel)).evaluate(
                new EvaluationRequest(question, List.of(new Document(evidence)), answer));

        assertTrue(response.isPass());
        Prompt prompt = capturedPrompts(1).getFirst();
        String rendered = prompt.getUserMessage().getText();
        assertTrue(rendered.contains(question));
        assertTrue(rendered.contains(evidence));
        assertTrue(rendered.contains(answer));
        assertFalse(rendered.contains("{query}"));
        assertFalse(rendered.contains("{response}"));
        assertFalse(rendered.contains("{context}"));
    }

    @Test
    void rejectsNegativeAndUnrecognizedRelevanceVerdicts() {
        when(judgeModel.call(any(Prompt.class)))
                .thenReturn(verdict("NO"))
                .thenReturn(verdict("YES because the answer is relevant."))
                .thenReturn(verdict("uncertain"));
        var evaluator = EvaluationJudges.relevancy(ChatClient.builder(judgeModel));
        var request = new EvaluationRequest("Question", List.of(new Document("Evidence")), "Answer");

        assertFalse(evaluator.evaluate(request).isPass());
        assertFalse(evaluator.evaluate(request).isPass());
        assertFalse(evaluator.evaluate(request).isPass());
        verify(judgeModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void rendersEvidenceAndClaimForFactualSupport() {
        when(judgeModel.call(any(Prompt.class))).thenReturn(verdict("yes"));
        String evidence = "Chat histories are kept in process memory and lost on restart.";
        String claim = "Restarting the app clears chat history.";

        var response = EvaluationJudges.factualSupport(ChatClient.builder(judgeModel)).evaluate(
                new EvaluationRequest(List.of(new Document(evidence)), claim));

        assertTrue(response.isPass());
        String rendered = capturedPrompts(1).getFirst().getUserMessage().getText();
        assertTrue(rendered.contains(evidence));
        assertTrue(rendered.contains(claim));
        assertFalse(rendered.contains("{document}"));
        assertFalse(rendered.contains("{claim}"));
    }

    @Test
    void rejectsUnsupportedClaimsAndProseInsteadOfAnExactFactVerdict() {
        when(judgeModel.call(any(Prompt.class)))
                .thenReturn(verdict(" no "))
                .thenReturn(verdict("YES, but the evidence is incomplete."));
        var evaluator = EvaluationJudges.factualSupport(ChatClient.builder(judgeModel));
        var request = new EvaluationRequest(List.of(new Document("The index is in memory.")),
                "The index persists forever in a remote database.");

        assertFalse(evaluator.evaluate(request).isPass());
        assertFalse(evaluator.evaluate(request).isPass());
        verify(judgeModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void keepsJudgingCallsIndependentOfConversationHistoryAndApplicationTools() {
        when(judgeModel.call(any(Prompt.class))).thenReturn(verdict("YES"));
        var evaluator = EvaluationJudges.relevancy(ChatClient.builder(judgeModel));
        evaluator.evaluate(new EvaluationRequest("First unique question",
                List.of(new Document("First unique evidence")), "First unique answer"));
        evaluator.evaluate(new EvaluationRequest("Second unique question",
                List.of(new Document("Second unique evidence")), "Second unique answer"));

        List<Prompt> prompts = capturedPrompts(2);
        assertEquals(1, prompts.getFirst().getInstructions().size());
        Prompt second = prompts.get(1);
        assertEquals(1, second.getInstructions().size());
        String rendered = second.getUserMessage().getText();
        assertTrue(rendered.contains("Second unique question"));
        assertTrue(rendered.contains("Second unique evidence"));
        assertFalse(rendered.contains("First unique"));
        ToolCallingChatOptions options = (ToolCallingChatOptions) second.getOptions();
        assertTrue(options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty());
        assertTrue(options.getToolContext() == null || options.getToolContext().isEmpty());
    }

    private List<Prompt> capturedPrompts(int count) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(judgeModel, times(count)).call(captor.capture());
        return captor.getAllValues();
    }

    private ChatResponse verdict(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
