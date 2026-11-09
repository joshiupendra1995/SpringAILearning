package com.springai.fundamentals.controller;

import com.springai.fundamentals.model.KnowledgeStatus;
import com.springai.fundamentals.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public KnowledgeStatus status() {
        return knowledgeService.status();
    }

    @PostMapping("/index")
    public Mono<KnowledgeStatus> index() {
        return Mono.fromCallable(knowledgeService::index)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(exception -> {
                    if (exception instanceof ResponseStatusException) {
                        return exception;
                    }
                    log.warn("Could not index the bundled learning notes.", exception);
                    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Could not index learning notes. Check that Ollama is running and the configured "
                                    + "embedding model is installed (default: nomic-embed-text).");
                });
    }
}
