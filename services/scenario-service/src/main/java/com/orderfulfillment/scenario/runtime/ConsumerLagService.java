package com.orderfulfillment.scenario.runtime;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real consumer-group lag, read straight from the broker via the admin API — the same computation
 * {@code kafka-consumer-groups.sh --describe} performs (per-partition latest offset minus the
 * group's last committed offset, summed). Exists for Scenario 8 (High-Volume Batch, Phase 10 —
 * Scaling Demo) so a scenario run can report a real, observed backlog instead of a guess, the same
 * way {@link OrderStatusWatcher} reports real order-status transitions instead of a scripted wait.
 */
@Component
public class ConsumerLagService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagService.class);
    private static final Duration ADMIN_CALL_TIMEOUT = Duration.ofSeconds(5);

    private final AdminClient adminClient;

    public ConsumerLagService(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.adminClient = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    /**
     * Total lag (sum across every partition of {@code topic} the group has committed an offset on)
     * for {@code groupId}. Returns {@code 0} if the group has no committed offsets yet (e.g. queried
     * before the group has ever consumed) or if the broker call fails — this is a measurement aid,
     * not a correctness gate, so a transient admin-API hiccup should not fail the scenario run.
     */
    public long totalLag(String groupId, String topic) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            List<TopicPartition> partitions = committed.keySet().stream()
                    .filter(tp -> tp.topic().equals(topic))
                    .toList();
            if (partitions.isEmpty()) {
                return 0L;
            }

            Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
            partitions.forEach(tp -> latestRequest.put(tp, OffsetSpec.latest()));
            ListOffsetsResult latestResult = adminClient.listOffsets(latestRequest);

            long lag = 0L;
            for (TopicPartition tp : partitions) {
                long endOffset = latestResult.partitionResult(tp)
                        .get(ADMIN_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS).offset();
                long committedOffset = committed.get(tp).offset();
                lag += Math.max(0, endOffset - committedOffset);
            }
            return lag;
        } catch (Exception e) {
            log.debug("Could not compute consumer lag for group {} / topic {}", groupId, topic, e);
            return 0L;
        }
    }

    @Override
    public void destroy() {
        adminClient.close(ADMIN_CALL_TIMEOUT);
    }
}
