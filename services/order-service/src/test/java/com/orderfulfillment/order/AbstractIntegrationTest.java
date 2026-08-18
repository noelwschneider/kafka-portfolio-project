package com.orderfulfillment.order;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.orderfulfillment.common.kafka.EventPublisher;

/**
 * Base for Order Service's Testcontainers-backed integration tests: a real PostgreSQL instance
 * (migrated by Spring Boot's own Flyway auto-configuration, scoped to the {@code order_service}
 * schema) plus a real Kafka broker. Phase 3 boundary: unlike the Phase 1/2 monolith's single
 * integration-test base that exercised all four domains in one JVM, this base only ever starts
 * Order Service itself — Inventory/Payment/Fulfillment's own reactions are simulated by publishing
 * the same wire-format events they would have produced, using the same {@link EventPublisher} bean
 * this service uses for its own outbound events, so the JSON shape is identical to what a real
 * upstream service would send. This proves Order Service's own consumers/producers against the
 * frozen contract without standing up the other three services (implementation-phases.md's Phase 3
 * exit criteria + docs/agent-reports/phase-3-boundary.md).
 *
 * <p>Deliberately does NOT use {@code @Testcontainers}/{@code @Container} — see the Phase 2 report
 * (docs/agent-reports/phase-2.md) for why: that annotation pair restarts containers between test
 * classes and reassigns ports, which can strand a cached Spring test context on a dead port. A
 * singleton container started once in a static initializer (reaped by Testcontainers' Ryuk at JVM
 * exit) avoids this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("orderfulfillment")
                .withUsername("orderfulfillment")
                .withPassword("orderfulfillment");
        POSTGRES.start();

        KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    @Autowired
    EventPublisher eventPublisher;

    /**
     * For publishing records {@link EventPublisher} deliberately cannot produce — an envelope with
     * an {@code eventVersion} the codec rejects, or a payload that will not deserialize. Phase 4's
     * poison-message scenario needs a genuinely malformed record on the wire, not a mocked failure.
     */
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    /** Direct reads of the processed_events ledger, which has no JPA entity by design. */
    @Autowired
    JdbcClient jdbcClient;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void noop() {
        // no shared demo state to reset in this service
    }

    /** Raw consumer for asserting what this service published, independent of its own listener
     * container's consumer group. */
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

    static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
}
