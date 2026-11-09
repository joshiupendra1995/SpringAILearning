package com.springai.fundamentals.model;

public record ChatRequest(String message, String mode, String topic) {

    public ChatRequest(String message) {
        this(message, null, null);
    }

    public ChatRequest(String message, String mode) {
        this(message, mode, null);
    }
}
