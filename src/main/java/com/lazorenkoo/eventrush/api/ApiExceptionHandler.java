package com.lazorenkoo.eventrush.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return response(error, headers, status);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> businessError(ResponseStatusException error) {
        return response(error, error.getHeaders(), error.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpectedError(Exception error) {
        String reference = UUID.randomUUID().toString();
        log.error("API failure {}: {}", reference, error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(new ApiError(500, "INTERNAL_ERROR"));
    }

    private ResponseEntity<Object> response(Exception error, HttpHeaders headers, HttpStatusCode status) {
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "TOO_MANY_REQUESTS";
            default -> status.is5xxServerError() ? "SERVICE_UNAVAILABLE" : "REQUEST_REJECTED";
        };
        if (error instanceof ResponseStatusException business && business.getReason() != null
                && business.getReason().matches("[A-Z][A-Z0-9_]{0,63}")) {
            code = business.getReason();
        }
        return ResponseEntity.status(status).headers(headers).cacheControl(CacheControl.noStore())
                .body(new ApiError(status.value(), code));
    }

    public record ApiError(int status, String code) {
    }
}
