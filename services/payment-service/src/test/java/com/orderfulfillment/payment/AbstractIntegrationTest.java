package com.orderfulfillment.payment;

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
 * Base for Payment Service's Testcontainers-backed integration tests. See order-service's
 * {@code AbstractIntegrationTest} Javadoc for the shared rationale: only this service is started;
 * Order Service is simulated by publishing PaymentRequested directly, via this service's own
 * {@link EventPublisher} bean.
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

    @Autowired
    PaymentBehaviorStore paymentBehaviorStore;

    /** Direct reads of the processed_events ledger, which has no JPA entity by design. */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * For publishing records {@link EventPublisher} deliberately cannot produce — an envelope with
     * an {@code eventVersion} the codec rejects, or a payload that will not deserialize. Phase 4's
     * poison-message and duplicate-delivery scenarios need genuine records on the wire, not mocked
     * failures.
     */
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void resetPaymentBehavior() {
        paymentBehaviorStore.clear();
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

    static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
}
