package com.springai.fundamentals.exception;

import com.springai.fundamentals.model.ApiError;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason() == null ? "Request could not be completed." : exception.getReason();
        return ResponseEntity.status(status).body(new ApiError(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        if (hasCause(exception, ReadTimeoutException.class)) {
            log.warn("Ollama did not respond before the configured response timeout.", exception);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(new ApiError(
                            "Ollama took too long to respond. Wait for the model to finish loading, then try again."));
        }

        log.error("Unexpected failure while handling an AI chat request.", exception);
        if (hasCause(exception, WebClientRequestException.class)) {
            return ResponseEntity.internalServerError()
                    .body(new ApiError(
                            "Could not connect to Ollama. Make sure Ollama is running and llama3:latest is available."));
        }
        return ResponseEntity.internalServerError()
                .body(new ApiError("Ollama could not complete the request. Please try again."));
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        return ExceptionUtils.indexOfThrowable(exception, causeType) >= 0;
    }
}
