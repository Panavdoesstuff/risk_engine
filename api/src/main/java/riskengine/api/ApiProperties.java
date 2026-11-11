package riskengine.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("api")
public record ApiProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits for HS256)") String jwtSecret,
        @NotBlank String jwtIssuer,
        @NotBlank String kafkaBootstrap,
        @NotBlank String eventsTopic,
        @Min(1) int partitions,
        @NotNull @Valid RateLimit rateLimit,
        @NotNull Duration requestIdTtl,
        @Min(1) long maxOrderQty) {

    /**
     * @param capacity        most requests a user can make in one burst
     * @param refillPerSecond sustained requests per second per user
     */
    public record RateLimit(@Min(1) int capacity, @DecimalMin(value = "0.0", inclusive = false) double refillPerSecond) {}
}
