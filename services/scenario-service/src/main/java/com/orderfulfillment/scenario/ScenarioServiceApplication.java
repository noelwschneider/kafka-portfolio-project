package com.orderfulfillment.scenario;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Scenario Service — Phase 5. The demo/fault-injection control layer (docs/openapi/scenario-service.yaml),
 * running standalone on port 8085. It talks to the other four services only through their own public
 * HTTP APIs (control plane, see the OpenAPI doc's "How a run is composed") and by producing/consuming
 * Kafka records directly — never by touching another service's database.
 *
 * <p>See {@code com.orderfulfillment.payment.PaymentServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}, and why
 * the {@link TypeExcludeFilter} exclude filter is restored by hand.
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
@ComponentScan(
        basePackages = {"com.orderfulfillment.scenario", "com.orderfulfillment.common"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class ScenarioServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScenarioServiceApplication.class, args);
    }
}
