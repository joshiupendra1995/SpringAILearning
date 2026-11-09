package com.springai.fundamentals.advisor;

import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

/** Stops generation when the preceding retrieval advisor found no supporting documents. */
public final class RagGroundingAdvisor implements StreamAdvisor {

    private static final String EMPTY_CONTEXT_ANSWER = "I couldn't find relevant information in the indexed "
            + "learning notes. Try a question about Spring AI, RAG, embeddings, or tool calling, or use Tutor mode.";

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // QuestionAnswerAdvisor is explicitly configured with order 0 on this application's RAG path.
        return 1;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Object retrieved = request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
            if (!(retrieved instanceof List<?> documents)
                    || documents.stream().anyMatch(document -> !(document instanceof Document))) {
                return Flux.error(new IllegalStateException(
                        "RAG grounding requires documents from the preceding QuestionAnswerAdvisor."));
            }
            if (!documents.isEmpty()) {
                return chain.nextStream(request);
            }
            ChatResponse refusal = new ChatResponse(List.of(new Generation(new AssistantMessage(EMPTY_CONTEXT_ANSWER))));
            return Flux.just(ChatClientResponse.builder()
                    .chatResponse(refusal)
                    .context(request.context())
                    .build());
        });
    }
}
