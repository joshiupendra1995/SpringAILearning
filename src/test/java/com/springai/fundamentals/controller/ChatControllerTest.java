package com.springai.fundamentals.controller;

import com.springai.fundamentals.model.ChatRequest;
import com.springai.fundamentals.model.RagTopic;
import java.util.List;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
    void defaultsToRagAndStreamsAnswerAndCompletionEvent() {
        when(chatService.stream("What is a token?", "session-123", "rag"))
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

        verify(chatService).stream("What is a token?", "session-123", "rag");
        verify(session).start();
    }

    @Test
    void sendsStreamErrorsAsErrorEvents() {
        when(chatService.stream("What is a token?", "session-123", "rag"))
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
    void routesRagRequestsToTheSelectedMode() {
        when(chatService.stream("What is RAG?", "session-123", "rag"))
                .thenReturn(Flux.just("Retrieve supporting passages."));

        StepVerifier.create(chatController.chat(new ChatRequest("What is RAG?", "rag"), session))
                .assertNext(event -> {
                    assertEquals("token", event.event());
                    assertEquals("Retrieve supporting passages.", event.data());
                })
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();
    }

    @Test
    void usesTheGeneralTutorOnlyWhenExplicitlySelected() {
        when(chatService.stream("Explain tokens", "session-123", "tutor"))
                .thenReturn(Flux.just("A token is a piece of text."));

        StepVerifier.create(chatController.chat(new ChatRequest("Explain tokens", "tutor"), session))
                .assertNext(event -> assertEquals("A token is a piece of text.", event.data()))
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();

        verify(chatService).stream("Explain tokens", "session-123", "tutor");
    }

    @Test
    void explainsWhenPreparingTheRagNotesFails() {
        when(chatService.stream("What is RAG?", "session-123", "rag"))
                .thenReturn(Flux.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not prepare the learning notes. Check Ollama and the embedding model.")));

        StepVerifier.create(chatController.chat(new ChatRequest("What is RAG?", "rag"), session))
                .assertNext(event -> {
                    assertEquals("error", event.event());
                    assertEquals("Could not prepare the learning notes. Check Ollama and the embedding model.", event.data());
                })
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();
    }

    @Test
    void passesOnlyValidatedTopicChoicesToTheRagService() {
        when(chatService.stream("How are tools executed?", "session-123", "rag", RagTopic.TOOLS))
                .thenReturn(Flux.just("Application code executes a tool."));

        StepVerifier.create(chatController.chat(
                        new ChatRequest("How are tools executed?", "rag", "tools"), session))
                .assertNext(event -> assertEquals("Application code executes a tool.", event.data()))
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();

        verify(chatService).stream("How are tools executed?", "session-123", "rag", RagTopic.TOOLS);
    }

    @Test
    void rejectsRawFilterExpressionsAndUnknownTopicsBeforeStartingTheSession() {
        for (String topic : List.of("unknown", "", "rag' || topic == 'tools", "topic == 'rag'")) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> chatController.chat(new ChatRequest("Hello", "rag", topic), session));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
            assertEquals("Topic must be all, rag, spring-ai, or tools.", exception.getReason());
        }
        verifyNoInteractions(chatService);
        verify(session, org.mockito.Mockito.never()).start();
    }

    @Test
    void rejectsTopicFieldsOutsideRagMode() {
        for (String mode : List.of("tutor", "tools", "mcp")) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> chatController.chat(new ChatRequest("Hello", mode, "all"), session));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
            assertEquals("Topic is only supported in rag mode.", exception.getReason());
        }
        verifyNoInteractions(chatService);
        verify(session, org.mockito.Mockito.never()).start();
    }

    @Test
    void rejectsUnknownModesBeforeStartingASessionOrCallingTheModel() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> chatController.chat(new ChatRequest("Hello", "unknown"), session));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(chatService);
        verify(session, org.mockito.Mockito.never()).start();
    }

    @Test
    void clearsMemoryForTheCurrentWebSession() {
        StepVerifier.create(chatController.clearConversation(session)).verifyComplete();

        verify(chatService).clearConversation("session-123");
        verify(session).start();
    }
}
