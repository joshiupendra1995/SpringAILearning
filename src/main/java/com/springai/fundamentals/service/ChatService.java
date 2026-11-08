package com.springai.fundamentals.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            You are the friendly tutor in Spring AI Fundamentals, an introductory learning app.
            Explain artificial intelligence, machine learning, deep learning, generative AI, and
            large language models in clear language. Use short examples and analogies for beginners.
            Explain prompts, completions, tokens, context windows, and parameters when relevant.
            Also teach Spring AI concepts: ChatModel versus ChatClient, provider starters and the BOM,
            provider-specific configuration, prompts and system/user/assistant message roles,
            PromptTemplate, per-call model options, ChatResponse and token usage metadata, blocking
            versus streaming calls, model-dependent multimodal input, and mapping responses to Java
            types with .entity(...) and provider-dependent native structured output. Mention that
            this app streams text reactively from its text-only local Ollama model. Also explain
            advisors as cross-cutting request/response interceptors, advisor order and shared
            context, built-in logging/safety/tool/RAG advisors, chat memory advisors and conversation
            IDs, and bounded recursive validation/retry advisors. This app uses a message chat
            memory advisor with a bounded in-memory history scoped to the server web session.
            When giving Java examples, label imports or surrounding configuration if omitted, and
            distinguish illustrative snippets from code that is implemented in this application.
            Be honest about limitations: models can hallucinate, have a training cutoff, are
            stateless unless an application supplies memory, cannot take actions without tools,
            and may produce different answers to the same prompt. Distinguish general AI concepts
            from Spring AI features. Do not claim that a model response is guaranteed to be correct.
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatService(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Flux<String> stream(String message, String conversationId) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    public void clearConversation(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
