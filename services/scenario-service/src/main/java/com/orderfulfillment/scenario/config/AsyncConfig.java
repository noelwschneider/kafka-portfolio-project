package com.orderfulfillment.scenario.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor a scenario run's asynchronous harness runs on (docs/openapi/scenario-service.yaml:
 * {@code POST /demo/scenarios/{scenarioName}} returns 202 immediately and the run proceeds in the
 * background). Sized small — this is a demo control plane driving at most a handful of concurrent
 * runs, not a production workload.
 */
@Configuration
public class AsyncConfig {

    @Bean("scenarioExecutor")
    public Executor scenarioExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("scenario-run-");
        executor.initialize();
        return executor;
    }
}
