package riskengine.api;

import org.springframework.http.HttpStatus;

/** A request the API refuses, with the status and body to answer with. */
public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final transient ApiError body;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, new ApiError(error, message));
    }

    public ApiException(HttpStatus status, ApiError body) {
        super(body.error());
        this.status = status;
        this.body = body;
    }

    public HttpStatus status() {
        return status;
    }

    public ApiError body() {
        return body;
    }
}
