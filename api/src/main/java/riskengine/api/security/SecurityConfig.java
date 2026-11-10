package riskengine.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import riskengine.api.ApiError;
import riskengine.api.ApiProperties;
import riskengine.api.limits.RateLimitFilter;
import riskengine.api.limits.RateLimiter;

/**
 * Who gets in, in this order:
 *
 * <ol>
 *   <li><b>401</b> unless the request carries a token that is signed with our key (HS256 only), from our
 *       issuer, not expired, and has a known role (and, for a trader, an account).</li>
 *   <li><b>429</b> if that user is over their rate limit (checked before any work is done for them).</li>
 *   <li><b>403</b> if the role may not use the endpoint. Whether a trader may touch a particular account
 *       is checked in the controller, where the account id is known.</li>
 * </ol>
 *
 * Stateless: no session, no cookies, so there is nothing for CSRF to ride on.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, RateLimiter limiter, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            ApiError.write(response, json, HttpStatus.UNAUTHORIZED, new ApiError("unauthenticated", "a valid bearer token is required"));
        };
        AccessDeniedHandler forbidden = (request, response, e) ->
                ApiError.write(response, json, HttpStatus.FORBIDDEN, new ApiError("forbidden", "your role may not do this"));
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole(Caller.Role.TRADER.name())
                        .requestMatchers(HttpMethod.GET, "/orders/*").hasAnyRole(Caller.Role.TRADER.name(), Caller.Role.RISK_OPS.name())
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authorities()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
                .addFilterAfter(new RateLimitFilter(limiter, json), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(ApiProperties props) {
        // Only HS256 with our key is accepted. A token that names another algorithm (including "none")
        // fails here, whatever it claims.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(JwtTokens.key(props.jwtSecret())).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(props.jwtIssuer()), SecurityConfig::claims));
        return decoder;
    }

    /** Claims every token must carry. A token that is validly signed but incomplete is still not a login. */
    private static OAuth2TokenValidatorResult claims(Jwt jwt) {
        String problem = null;
        Caller.Role role = Caller.Role.fromClaim(jwt.getClaimAsString(JwtTokens.ROLE));
        String account = jwt.getClaimAsString(JwtTokens.ACCOUNT);
        if (jwt.getExpiresAt() == null) problem = "token has no expiry";
        else if (jwt.getSubject() == null || !JwtTokens.USER_ID.matcher(jwt.getSubject()).matches()) problem = "token has no usable subject";
        else if (role == null) problem = "token has no known role";
        else if (role == Caller.Role.TRADER && (account == null || !JwtTokens.ACCOUNT_ID.matcher(account).matches())) problem = "trader token has no account";
        return problem == null ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", problem, null));
    }

    private static JwtAuthenticationConverter authorities() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority(Caller.of(jwt).role().authority())));
        return c;
    }
}
