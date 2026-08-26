package com.orderfulfillment.common.kafka;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Reports Kafka reachability under the {@code kafka} component key that
 * {@code frontend/src/pages/OverviewPage.tsx} reads from each service's {@code /actuator/health}
 * response. Spring Boot 4.1 dropped the auto-configured {@code KafkaHealthIndicator} that older
 * versions shipped (there is no {@code org.springframework.boot.actuate.kafka} package left in
 * {@code spring-boot-health} 4.1.0 — {@code describeCluster()} against the admin API is the
 * replacement this project defines), so every component's Kafka row has read "no data" since Sprint
 * 4 enabled {@code show-components: always} (see each service's {@code application.yml}).
 *
 * <p>Lives in {@code common} rather than being duplicated five times: every one of the five services
 * declares {@code com.orderfulfillment.common} as an explicit {@code @ComponentScan} base package
 * already (see e.g. {@code InventoryServiceApplication}'s Javadoc), all five carry both
 * {@code spring-kafka} and {@code spring-boot-starter-actuator}, and all five connect to the same
 * broker via {@code spring.kafka.bootstrap-servers} — there is no per-service variation for this
 * check to encode. This mirrors {@link KafkaTopicConfig}, the other Kafka infrastructure bean common
 * already contributes to every service.
 *
 * <p><b>Healthy means:</b> a {@code describeCluster()} call against the real broker, issued through a
 * dedicated {@link AdminClient} (not the producer/consumer factories the rest of the application
 * uses), returns at least one node within {@link #ADMIN_CALL_TIMEOUT}. That is a genuine round trip
 * to the broker's metadata API — the same call {@code kafka-broker-api-versions.sh} and
 * scenario-service's {@code ConsumerLagService} lag queries rely on — not a check of whether a
 * producer/consumer bean merely exists in the Spring context. Anything else (timeout, no nodes,
 * connection refused) reports {@code DOWN} with the exception detail attached.
 */
@Component
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final Duration ADMIN_CALL_TIMEOUT = Duration.ofSeconds(3);

    private final AdminClient adminClient;

    public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        super("Kafka health check failed");
        this.adminClient = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) ADMIN_CALL_TIMEOUT.toMillis()));
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        DescribeClusterResult result = adminClient.describeCluster();
        String clusterId = result.clusterId().get(ADMIN_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        Collection<Node> nodes = result.nodes().get(ADMIN_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        if (nodes.isEmpty()) {
            builder.down().withDetail("reason", "describeCluster() returned no brokers");
            return;
        }

        builder.up().withDetail("clusterId", clusterId).withDetail("nodeCount", nodes.size());
    }

    @Override
    public void destroy() {
        adminClient.close(Duration.ofSeconds(1));
    }
}
