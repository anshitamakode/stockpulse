package com.stockpulse.exception;

/** Mapped to HTTP 400 by GlobalExceptionHandler. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
