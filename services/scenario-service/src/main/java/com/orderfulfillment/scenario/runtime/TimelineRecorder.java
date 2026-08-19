package com.orderfulfillment.scenario.runtime;

import com.orderfulfillment.scenario.domain.ScenarioRunTimelineEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunTimelineRepository;
import com.orderfulfillment.scenario.domain.TimelineKind;
import com.orderfulfillment.scenario.dto.ScenarioTimelineEntryDto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends one timeline entry at a time, persists it, then pushes it over SSE — in that order, so a
 * subscriber never sees an entry that isn't durable yet. Two independent threads append to the same
 * run concurrently in general (the scenario harness thread for HTTP/STATE_CHANGE entries, and one or
 * more Kafka listener threads for EVENT entries), so sequence assignment is synchronized per run id.
 *
 * <p>{@code detail} is intentionally an open map: per docs/openapi/scenario-service.yaml's
 * ScenarioTimelineEntry schema, only fields the caller actually observed are included — never a
 * fabricated placeholder.
 */
@Component
public class TimelineRecorder {

    private final ScenarioRunTimelineRepository timelineRepository;
    private final RunEventHub eventHub;
    private final ObjectMapper objectMapper;
    private final Map<String, AtomicInteger> sequenceByRun = new ConcurrentHashMap<>();
    private final Map<String, Object> locksByRun = new ConcurrentHashMap<>();

    public TimelineRecorder(ScenarioRunTimelineRepository timelineRepository, RunEventHub eventHub,
                             ObjectMapper objectMapper) {
        this.timelineRepository = timelineRepository;
        this.eventHub = eventHub;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScenarioTimelineEntryDto append(String runId, TimelineKind kind, String label, Map<String, Object> detail) {
        Object lock = locksByRun.computeIfAbsent(runId, k -> new Object());
        int sequence;
        Instant occurredAt = Instant.now();
        String detailJson = (detail == null || detail.isEmpty()) ? null : objectMapper.writeValueAsString(detail);
        synchronized (lock) {
            sequence = sequenceByRun.computeIfAbsent(runId, k -> new AtomicInteger(0)).incrementAndGet();
            ScenarioRunTimelineEntity entity =
                    new ScenarioRunTimelineEntity(runId, sequence, kind, label, occurredAt, detailJson);
            timelineRepository.save(entity);
        }
        ScenarioTimelineEntryDto dto = new ScenarioTimelineEntryDto(
                sequence, kind.name(), label, occurredAt, detail == null ? Map.of() : new LinkedHashMap<>(detail));
        eventHub.publishTimelineEntry(runId, dto);
        return dto;
    }

    public void forget(String runId) {
        sequenceByRun.remove(runId);
        locksByRun.remove(runId);
    }
}
