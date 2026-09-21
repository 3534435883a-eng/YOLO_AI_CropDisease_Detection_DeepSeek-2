package com.example.Ece.service;

public class DeepSeekException extends RuntimeException {
    private final String code;

    public DeepSeekException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
