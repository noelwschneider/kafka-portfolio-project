package com.orderfulfillment.scenario;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Base for Scenario Service's Testcontainers-backed integration tests. Only this service is started
 * (real Testcontainers Kafka + Postgres), matching the isolation convention every other service's
 * tests already follow — see payment-service's {@code AbstractIntegrationTest} Javadoc. The four
 * downstream services Scenario Service calls over REST are stood in for by one
 * {@link WireMockServer} per service, the same role direct-Kafka-publish plays in the other services'
 * suites. The one path this cannot cover — Scenario Service driving the *real* four services — is
 * exercised live and manually instead (docs/agent-reports/phase-5-scenario-service.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Each concrete test class gets its own Spring context (same underlying Testcontainers Postgres/
// Kafka/WireMock, started once in the static initializer below): RunRegistry, the scenario-executor
// thread pool, and Kafka consumer group state are all singletons, and a background scenario run
// started by one test class can still be executing on its thread pool after that test method
// returns — sharing a context across classes let that leak into later classes' 409 checks.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA;
    static final WireMockServer ORDER_SERVICE;
    static final WireMockServer INVENTORY_SERVICE;
    static final WireMockServer PAYMENT_SERVICE;
    static final WireMockServer FULFILLMENT_SERVICE;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("orderfulfillment")
                .withUsername("orderfulfillment")
                .withPassword("orderfulfillment");
        POSTGRES.start();

        KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
        KAFKA.start();

        ORDER_SERVICE = new WireMockServer(options().dynamicPort());
        INVENTORY_SERVICE = new WireMockServer(options().dynamicPort());
        PAYMENT_SERVICE = new WireMockServer(options().dynamicPort());
        FULFILLMENT_SERVICE = new WireMockServer(options().dynamicPort());
        ORDER_SERVICE.start();
        INVENTORY_SERVICE.start();
        PAYMENT_SERVICE.start();
        FULFILLMENT_SERVICE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("orderfulfillment.services.order-service", () -> "http://localhost:" + ORDER_SERVICE.port());
        registry.add("orderfulfillment.services.inventory-service",
                () -> "http://localhost:" + INVENTORY_SERVICE.port());
        registry.add("orderfulfillment.services.payment-service", () -> "http://localhost:" + PAYMENT_SERVICE.port());
        registry.add("orderfulfillment.services.fulfillment-service",
                () -> "http://localhost:" + FULFILLMENT_SERVICE.port());
    }

    @LocalServerPort
    int port;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void resetStubs() {
        ORDER_SERVICE.resetAll();
        INVENTORY_SERVICE.resetAll();
        PAYMENT_SERVICE.resetAll();
        FULFILLMENT_SERVICE.resetAll();
    }

    Consumer<String, String> rawConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + java.util.UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    static final Duration POLL_TIMEOUT = Duration.ofSeconds(20);

    /** Scripts GET /api/orders/{id} on {@link #ORDER_SERVICE} to walk through {@code statuses} in
     * order, one new status per poll, staying on the last one forever after. */
    void stubOrderLifecycle(String orderId, String... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            // WireMock scenarios start in the fixed state "Started", not a name of our choosing —
            // the first stub must key off that exact constant or it never matches the first request.
            String fromState = i == 0 ? com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED : "STATE_" + i;
            String toState = "STATE_" + Math.min(i + 1, statuses.length - 1);
            ORDER_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                    .get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/api/orders/" + orderId))
                    .inScenario("order-lifecycle-" + orderId).whenScenarioStateIs(fromState)
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"" + orderId + "\",\"status\":\"" + statuses[i] + "\"}"))
                    .willSetStateTo(toState));
        }
    }
}
