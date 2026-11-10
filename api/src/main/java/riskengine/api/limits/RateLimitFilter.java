package riskengine.api.limits;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import riskengine.api.ApiError;

/**
 * Applies the per-user rate limit. Sits right after token verification: by then we know who the user is,
 * and nothing has been done for them yet, so a refused request costs one Redis call and no more.
 *
 * <p>Not a Spring bean on purpose: Spring Boot would also register a bean filter with the servlet
 * container and every request would be counted twice. The security configuration creates it.
 */
public final class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final ObjectMapper json;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication who = SecurityContextHolder.getContext().getAuthentication();
        if (who instanceof JwtAuthenticationToken token) {
            long waitMillis;
            try {
                waitMillis = limiter.tryAcquire(token.getName());
            } catch (DataAccessException e) {
                // Redis is down. Fail closed: letting everything through would switch the limit off
                // exactly when the system is already in trouble.
                log.error("rate limiter unavailable", e);
                response.setHeader("Retry-After", "1");
                ApiError.write(response, json, HttpStatus.SERVICE_UNAVAILABLE, new ApiError("unavailable", "try again shortly with the same X-Request-Id"));
                return;
            }
            if (waitMillis > 0) {
                response.setHeader("Retry-After", Long.toString((waitMillis + 999) / 1000));
                ApiError.write(response, json, HttpStatus.TOO_MANY_REQUESTS, new ApiError("rate_limited", "too many requests; slow down"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
