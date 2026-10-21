package com.stockpulse.exception;

/** Mapped to HTTP 409 by GlobalExceptionHandler. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
