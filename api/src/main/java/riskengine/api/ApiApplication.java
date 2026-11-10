package riskengine.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The order API. It is asynchronous: POST /orders validates the request, writes an OrderRequest to the
 * account's partition of engine-events and answers 202 with an order id. The shard that owns the account
 * does the pre-trade margin check and publishes accept/reject to engine-out; GET /orders/{id} shows it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
