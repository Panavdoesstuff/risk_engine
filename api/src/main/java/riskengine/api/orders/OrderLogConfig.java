package riskengine.api.orders;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import riskengine.api.ApiProperties;
import riskengine.host.RedpandaLogs;

@Configuration
class OrderLogConfig {
    /**
     * The API's write side of engine-events. Creating it checks that the topic exists with the configured
     * number of partitions, so an API pointed at the wrong N refuses to start instead of misrouting orders.
     */
    @Bean(destroyMethod = "close")
    RedpandaLogs engineEvents(ApiProperties props) {
        return new RedpandaLogs(props.kafkaBootstrap(), props.eventsTopic(), props.partitions(), Duration.ofMillis(100));
    }

    /** The API is outside the engine, so it may read the wall clock: an order's log time is when it arrived. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
