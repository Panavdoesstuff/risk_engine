package riskengine.api;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import riskengine.host.RedpandaLogs.EventLogUnavailableException;

/** Turns exceptions into {@link ApiError} bodies. Anything not listed here is a plain 500 with no detail. */
@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> refused(ApiException e) {
        return ResponseEntity.status(e.status()).body(e.body());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().toList();
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", "the order is not valid", null, fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        // Jackson's message can echo the caller's input and our class names; neither belongs in a response.
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", "the request body is not a valid order"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> wrongType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError("unsupported_media_type", "send application/json"));
    }

    /**
     * Redis or Redpanda is unreachable. The API fails closed: without Redis there is no rate limit and no
     * replay protection, and without the log there is nowhere to put the order.
     */
    @ExceptionHandler({DataAccessException.class, EventLogUnavailableException.class})
    ResponseEntity<ApiError> dependencyDown(Exception e) {
        log.error("dependency unavailable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(new ApiError("unavailable", "try again shortly with the same X-Request-Id"));
    }
}
