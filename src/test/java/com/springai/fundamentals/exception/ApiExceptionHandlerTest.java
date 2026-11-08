package com.springai.fundamentals.exception;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ApiExceptionHandlerTest {

    @Test
    void explainsOllamaReadTimeouts() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        var response = handler.handleUnexpected(new RuntimeException("Request failed", ReadTimeoutException.INSTANCE));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals(
                "Ollama took too long to respond. Wait for the model to finish loading, then try again.",
                response.getBody().error());
    }

    @Test
    void explainsOllamaConnectionFailures() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        WebClientRequestException connectionFailure = mock(WebClientRequestException.class);

        var response = handler.handleUnexpected(new RuntimeException("Connection failed", connectionFailure));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(
                "Could not connect to Ollama. Make sure Ollama is running and llama3:latest is available.",
                response.getBody().error());
    }
}
