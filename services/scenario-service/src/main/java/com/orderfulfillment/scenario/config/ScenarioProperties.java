package com.orderfulfillment.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orderfulfillment.scenario")
public record ScenarioProperties(
        long consumerOutagePauseMs,
        long orderPollIntervalMs,
        long orderPollTimeoutMs
) {
}
