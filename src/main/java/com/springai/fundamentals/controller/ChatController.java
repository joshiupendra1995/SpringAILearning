package com.springai.fundamentals.controller;

import com.springai.fundamentals.model.ChatRequest;
import com.springai.fundamentals.service.ChatService;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final int MAX_PROMPT_LENGTH = 2_000;

    private final ChatService chatService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request, WebSession session) {
        if (StringUtils.isBlank(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be blank.");
        }
        if (request.message().length() > MAX_PROMPT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message must be at most " + MAX_PROMPT_LENGTH + " characters.");
        }

        session.start();
        return chatService.stream(request.message(), session.getId())
                .map(token -> ServerSentEvent.builder(token).event("token").build())
                .onErrorResume(exception -> {
                    log.warn("Ollama chat stream failed.", exception);
                    return Flux.just(ServerSentEvent.builder(errorMessage(exception))
                            .event("error")
                            .build());
                })
                .concatWithValues(ServerSentEvent.builder("").event("done").build());
    }

    @DeleteMapping
    public Mono<Void> clearConversation(WebSession session) {
        session.start();
        return Mono.fromRunnable(() -> chatService.clearConversation(session.getId()));
    }

    private String errorMessage(Throwable exception) {
        if (hasCause(exception, ReadTimeoutException.class)) {
            return "Ollama took too long to respond. Wait for the model to finish loading, then try again.";
        }
        if (hasCause(exception, WebClientRequestException.class)) {
            return "Could not connect to Ollama. Make sure Ollama is running and llama3:latest is available.";
        }
        return "Ollama could not complete the request. Please try again.";
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        return ExceptionUtils.indexOfThrowable(exception, causeType) >= 0;
    }
}
