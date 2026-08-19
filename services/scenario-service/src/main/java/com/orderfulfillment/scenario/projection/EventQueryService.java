package com.orderfulfillment.scenario.projection;

import com.orderfulfillment.scenario.domain.EventRecordEntity;
import com.orderfulfillment.scenario.domain.EventRecordRepository;
import com.orderfulfillment.scenario.dto.EventRecordDto;
import com.orderfulfillment.scenario.dto.EventRecordPageDto;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Backs the Phase 5 addition {@code GET /demo/events} — the Event Explorer query endpoint. */
@Service
public class EventQueryService {

    private final EventRecordRepository repository;
    private final ObjectMapper objectMapper;

    public EventQueryService(EventRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EventRecordPageDto search(String eventType, String aggregateId, UUID correlationId, String producer,
                                      String topic, Boolean deadLettered, int page, int size) {
        Page<EventRecordEntity> result = repository.search(
                eventType, aggregateId, correlationId, producer, topic, deadLettered, PageRequest.of(page, size));
        return new EventRecordPageDto(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @SuppressWarnings("unchecked")
    private EventRecordDto toDto(EventRecordEntity e) {
        Map<String, Object> payload = objectMapper.readValue(e.getPayload(), Map.class);
        return new EventRecordDto(
                e.getEventId(), e.getEventType(), e.getEventVersion(), e.getOccurredAt(), e.getCorrelationId(),
                e.getAggregateId(), e.getTopic(), e.getPartition(), e.getOffset(), e.getProducer(),
                e.isDeadLettered(), payload);
    }
}
