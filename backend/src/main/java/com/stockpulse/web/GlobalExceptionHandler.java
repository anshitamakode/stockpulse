package com.stockpulse.web;

import com.stockpulse.exception.BadRequestException;
import com.stockpulse.exception.ConflictException;
import com.stockpulse.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/** Every error leaves as RFC 9457 ProblemDetail JSON. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail concurrentUpdate(OptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrent update",
                "Someone else changed this record at the same moment. Refresh and try again.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body has invalid fields");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Malformed request: " + rootMessage(e));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? e.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) + "…" : m);
    }
}
