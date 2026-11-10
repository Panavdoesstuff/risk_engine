package riskengine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * The body of every error response. Messages say what the caller can fix and nothing about internals.
 *
 * @param error   stable machine-readable code, e.g. {@code rate_limited}
 * @param orderId only on a replayed request: the order the request id was first used for
 * @param fields  only on validation errors: what was wrong with which field
 */
public record ApiError(String error, String message, String orderId, List<String> fields) {
    public ApiError(String error, String message) {
        this(error, message, null, null);
    }

    /** For filters and security handlers, which run outside Spring MVC's message converters. */
    public static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
