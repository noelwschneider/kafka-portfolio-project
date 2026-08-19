package com.orderfulfillment.scenario.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRecordRepository extends JpaRepository<EventRecordEntity, Long> {

    List<EventRecordEntity> findByCorrelationIdOrderByOccurredAtAsc(UUID correlationId);

    boolean existsByEventIdAndAggregateId(UUID eventId, String aggregateId);

    boolean existsByTopicAndPartitionAndOffset(String topic, int partition, long offset);

    @Query("""
            select e from EventRecordEntity e
            where (:eventType is null or e.eventType = :eventType)
              and (:aggregateId is null or e.aggregateId = :aggregateId)
              and (:correlationId is null or e.correlationId = :correlationId)
              and (:producer is null or e.producer = :producer)
              and (:topic is null or e.topic = :topic)
              and (:deadLettered is null or e.deadLettered = :deadLettered)
            order by e.occurredAt desc
            """)
    Page<EventRecordEntity> search(@Param("eventType") String eventType,
                                    @Param("aggregateId") String aggregateId,
                                    @Param("correlationId") UUID correlationId,
                                    @Param("producer") String producer,
                                    @Param("topic") String topic,
                                    @Param("deadLettered") Boolean deadLettered,
                                    Pageable pageable);
}
