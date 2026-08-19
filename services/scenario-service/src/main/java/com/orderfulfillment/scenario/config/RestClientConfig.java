package com.orderfulfillment.scenario.config;

import com.orderfulfillment.common.CorrelationIdFilter;
import com.orderfulfillment.common.CorrelationIdHolder;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One {@link RestClient} per downstream service, base-URLed from {@link ServiceUrlsProperties}. These
 * are the only HTTP clients Scenario Service uses to drive a run — plain synchronous REST, per the
 * OpenAPI doc's explicit "This is a control plane, not workflow" justification for using synchronous
 * calls here despite Phase 3's general rule against them.
 *
 * <p>Every request carries the run's correlationId as {@code X-Correlation-Id}
 * ({@link CorrelationIdFilter#HEADER}), read from {@link CorrelationIdHolder} — each
 * {@code ScenarioRunner} wraps its whole run in {@link CorrelationIdHolder#runInScope}, so the
 * downstream service's own {@code CorrelationIdFilter} adopts the run's id instead of minting a new
 * random one. Without this, the events a scenario causes would not share one correlationId and the
 * whole "trace this run across all four services" property (docs/openapi/scenario-service.yaml) would
 * be false.
 */
@Configuration
public class RestClientConfig {

    private static RestClient.Builder withCorrelationId(RestClient.Builder builder) {
        return builder.requestInterceptor((request, body, execution) -> {
            UUID correlationId = CorrelationIdHolder.get();
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId.toString());
            }
            return execution.execute(request, body);
        });
    }

    // Named *RestClient, not *ServiceClient, so these don't collide with the @Component-scanned
    // wrapper classes of the same near-name in the clients package (Spring derives a bean name from
    // the class name for those, e.g. OrderServiceClient -> "orderServiceClient").
    @Bean
    public RestClient orderServiceRestClient(ServiceUrlsProperties props) {
        return withCorrelationId(RestClient.builder().baseUrl(props.orderService())).build();
    }

    @Bean
    public RestClient inventoryServiceRestClient(ServiceUrlsProperties props) {
        return withCorrelationId(RestClient.builder().baseUrl(props.inventoryService())).build();
    }

    @Bean
    public RestClient paymentServiceRestClient(ServiceUrlsProperties props) {
        return withCorrelationId(RestClient.builder().baseUrl(props.paymentService())).build();
    }

    @Bean
    public RestClient fulfillmentServiceRestClient(ServiceUrlsProperties props) {
        return withCorrelationId(RestClient.builder().baseUrl(props.fulfillmentService())).build();
    }
}
