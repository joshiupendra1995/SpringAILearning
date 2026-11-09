package com.springai.fundamentals.advisor;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagGroundingAdvisorTest {

    private final RagGroundingAdvisor advisor = new RagGroundingAdvisor();
    private final StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

    @Test
    void refusesAnEmptyRetrievalWithoutInvokingTheModelAndPreservesContext() {
        ChatClientRequest request = request(Map.of(
                QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(), "requestId", "request-123"));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .assertNext(response -> {
                    assertTrue(response.chatResponse().getResult().getOutput().getText()
                            .contains("couldn't find relevant information in the indexed learning notes"));
                    assertEquals(request.context(), response.context());
                })
                .verifyComplete();

        verifyNoInteractions(chain);
    }

    @Test
    void delegatesFoundDocumentsWithoutChangingTheRequestOrResponse() {
        ChatClientRequest request = request(Map.of(
                QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(new Document("Relevant learning note."))));
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("Grounded answer.")))))
                .context(request.context())
                .build();
        when(chain.nextStream(request)).thenReturn(Flux.just(response));

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(response)
                .verifyComplete();

        verify(chain).nextStream(request);
    }

    @Test
    void failsClosedWhenRetrievalContextIsAbsent() {
        StepVerifier.create(advisor.adviseStream(request(Map.of()), chain))
                .expectError(IllegalStateException.class)
                .verify();

        verifyNoInteractions(chain);
    }

    @Test
    void failsClosedWhenRetrievalContextHasTheWrongType() {
        for (Object invalid : List.of("unexpected context", List.of("not a Document"))) {
            StepVerifier.create(advisor.adviseStream(request(Map.of(
                            QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, invalid)), chain))
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        verifyNoInteractions(chain);
    }

    @Test
    void runsAfterTheConfiguredRetrievalAdvisor() {
        QuestionAnswerAdvisor retrieval = QuestionAnswerAdvisor.builder(mock(VectorStore.class)).order(0).build();

        assertTrue(retrieval.getOrder() < advisor.getOrder());
        assertEquals("RagGroundingAdvisor", advisor.getName());
    }

    private ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder().prompt(new Prompt("What is RAG?")).context(context).build();
    }
}
