package com.orderfulfillment.monolith;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import com.orderfulfillment.monolith.payment.PaymentBehaviorStore;

/**
 * Base for Testcontainers-backed integration tests: a real PostgreSQL instance, migrated by the
 * same SchemaMigrationRunner the app uses at startup (docs/planning/implementation-phases.md's
 * Phase 1 exit criteria call for integration tests "against a real PostgreSQL").
 *
 * <p>Deliberately does NOT use @Testcontainers/@Container: that annotation pair stops and
 * restarts the container between test classes, which reassigns its mapped port — and because
 * Spring's test-context cache can outlive that restart, a later test class can end up talking to
 * a HikariDataSource still wired to the old (now-dead) port. Starting the single shared container
 * once in a static initializer and never stopping it (Testcontainers' Ryuk reaper cleans it up at
 * JVM exit) is the standard "singleton container" pattern that avoids this.
 *
 * <p>Uses RestTestClient (Spring Framework 7 / Boot 4's replacement for TestRestTemplate, which
 * was removed) bound to the random port assigned at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("orderfulfillment")
                .withUsername("orderfulfillment")
                .withPassword("orderfulfillment");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    PaymentBehaviorStore paymentBehaviorStore;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void resetPaymentBehavior() {
        paymentBehaviorStore.clear();
    }
}
