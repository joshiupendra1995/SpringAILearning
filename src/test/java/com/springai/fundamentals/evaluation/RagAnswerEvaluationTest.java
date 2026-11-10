package com.springai.fundamentals.evaluation;

import com.springai.fundamentals.model.RagAnswer;
import com.springai.fundamentals.model.RagTopic;
import com.springai.fundamentals.service.ChatService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real model checks: excluded from mvn test; run with mvn -Pai-evaluation verify. */
@Tag("ai-evaluation")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagAnswerEvaluationTest {

    private static final String QUESTION = "What does this app do when no relevant learning notes are retrieved? "
            + "Answer in one or two sentences.";

    @Autowired
    private ChatService chatService;

    @Autowired
    private OllamaApi ollamaApi;

    @Value("${spring.ai.ollama.chat.model}")
    private String answerModel;

    @Value("${spring.ai.ollama.embedding.model}")
    private String embeddingModel;

    @Value("${OLLAMA_JUDGE_MODEL:llama3.1:latest}")
    private String judgeModel;

    @Value("${app.ollama.response-timeout:5m}")
    private Duration responseTimeout;

    private RagAnswer ragAnswer;
    private RelevancyEvaluator relevancy;
    private FactCheckingEvaluator factualSupport;
    private ObservedJudge observedJudge;

    @BeforeAll
    void prepareRealAnswerAndIsolatedJudge() {
        List<String> installedModels = assertDoesNotThrow(() -> ollamaApi.listModels().models().stream()
                        .map(OllamaApi.Model::name).toList(),
                "AI evaluation requires a running Ollama server at OLLAMA_BASE_URL. Start Ollama and pull "
                        + "the configured chat, embedding, and judge models before using -Pai-evaluation.");
        for (String required : List.of(answerModel, embeddingModel, judgeModel)) {
            assertTrue(installedModels.contains(withTag(required)),
                    () -> "Missing evaluation model: " + required + ". Run: ollama pull " + required
                            + ". Installed models: " + installedModels);
        }

        // A fresh client has no answer-generation memory, RAG advisors, or application tools.
        OllamaChatModel judge = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder().model(judgeModel).temperature(0.0).numPredict(16).build())
                .build();
        observedJudge = new ObservedJudge(judge);
        relevancy = EvaluationJudges.relevancy(ChatClient.builder(observedJudge));
        factualSupport = EvaluationJudges.factualSupport(ChatClient.builder(observedJudge));

        // The application's actual QAA path returns its own evidence. Never run another search here.
        ragAnswer = chatService.answerWithContext(QUESTION, "evaluation-" + UUID.randomUUID(), RagTopic.RAG)
                .block(responseTimeout.plusMinutes(1));
        assertNotNull(ragAnswer, "The RAG pipeline did not return an answer.");
        assertFalse(ragAnswer.answer().isBlank(), "The answer model returned no text.");
        assertFalse(ragAnswer.retrievedDocuments().isEmpty(),
                "Retrieval found no evidence. Inspect the notes, embedding model, and search threshold.");
        assertTrue(ragAnswer.retrievedDocuments().stream()
                .allMatch(document -> document.getText() != null && !document.getText().isBlank()));
    }

    @Test
    void answersTheQuestionUsingItsRetrievedContext() {
        assertTrue(evaluate(relevancy, ragAnswer.answer()).isPass(),
                () -> "The relevance judge returned " + observedJudge.verdict + " for this RAG answer: "
                        + ragAnswer.answer());
    }

    @Test
    void supportsTheGeneratedClaimsWithTheSameEvidence() {
        assertTrue(evaluate(factualSupport, ragAnswer.answer()).isPass(),
                () -> "The fact-checking judge returned " + observedJudge.verdict + " for this RAG answer: "
                        + ragAnswer.answer());
    }

    @Test
    void rejectsAnUnrelatedAnswer() {
        assertFalse(evaluate(relevancy, "To bake a cake, mix flour and eggs and heat the oven.").isPass(),
                "The relevance judge accepted an unrelated answer; inspect the judge model and rubric.");
    }

    @Test
    void rejectsAnUnsupportedClaim() {
        assertFalse(evaluate(factualSupport,
                "This application guarantees that every generated answer is correct.").isPass(),
                "The fact-checking judge accepted an unsupported claim; inspect the judge model and rubric.");
    }

    private EvaluationResponse evaluate(Evaluator evaluator, String response) {
        EvaluationResponse result = evaluator.evaluate(
                new EvaluationRequest(ragAnswer.question(), ragAnswer.retrievedDocuments(), response));
        String verdict = observedJudge.verdict == null ? "" : observedJudge.verdict.strip();
        assertTrue(verdict.equalsIgnoreCase("YES") || verdict.equalsIgnoreCase("NO"),
                () -> "The judge returned an invalid verdict: " + observedJudge.verdict
                        + ". Expected only YES or NO; inspect its model, token limit, and rubric.");
        return result;
    }

    private static String withTag(String model) {
        return model.lastIndexOf(':') > model.lastIndexOf('/') ? model : model + ":latest";
    }

    /** Records the real judge's reply without modifying its prompt or verdict. */
    private static final class ObservedJudge implements ChatModel {
        private final ChatModel delegate;
        private String verdict;

        private ObservedJudge(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            verdict = response.getResult() == null ? null : response.getResult().getOutput().getText();
            return response;
        }
    }
}
