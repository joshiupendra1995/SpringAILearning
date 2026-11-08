package com.springai.fundamentals.controller;

import com.springai.fundamentals.model.ChatRequest;
import com.springai.fundamentals.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private ChatService chatService;
    private ChatController chatController;
    private WebSession session;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        session = mock(WebSession.class);
        when(session.getId()).thenReturn("session-123");
        chatController = new ChatController(chatService);
    }

    @Test
    void streamsTutorAnswerAndCompletionEvent() {
        when(chatService.stream("What is a token?", "session-123"))
                .thenReturn(Flux.just("A token ", "is a piece of text."));

        StepVerifier.create(chatController.chat(new ChatRequest("What is a token?"), session))
                .assertNext(event -> {
                    assertEquals("token", event.event());
                    assertEquals("A token ", event.data());
                })
                .assertNext(event -> {
                    assertEquals("token", event.event());
                    assertEquals("is a piece of text.", event.data());
                })
                .assertNext(event -> {
                    assertEquals("done", event.event());
                    assertEquals("", event.data());
                })
                .verifyComplete();

        verify(chatService).stream("What is a token?", "session-123");
        verify(session).start();
    }

    @Test
    void sendsStreamErrorsAsErrorEvents() {
        when(chatService.stream("What is a token?", "session-123"))
                .thenReturn(Flux.error(new IllegalStateException("provider details")));

        StepVerifier.create(chatController.chat(new ChatRequest("What is a token?"), session))
                .assertNext(event -> {
                    assertEquals("error", event.event());
                    assertEquals("Ollama could not complete the request. Please try again.", event.data());
                })
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();
    }

    @Test
    void rejectsBlankMessages() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chatController.chat(new ChatRequest("  "), session));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsMessagesLongerThanTwoThousandCharacters() {
        String message = "a".repeat(2_001);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chatController.chat(new ChatRequest(message), session));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void clearsMemoryForTheCurrentWebSession() {
        StepVerifier.create(chatController.clearConversation(session)).verifyComplete();

        verify(chatService).clearConversation("session-123");
        verify(session).start();
    }
}
