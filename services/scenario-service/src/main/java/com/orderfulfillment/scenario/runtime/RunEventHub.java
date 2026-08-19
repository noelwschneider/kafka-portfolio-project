package com.orderfulfillment.scenario.runtime;

import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.dto.ScenarioTimelineEntryDto;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Backs {@code GET /demo/scenario-runs/{runId}/stream}. Emits {@code timeline-entry} for every
 * timeline row as it is actually persisted, and {@code run-status} on a status change — never before
 * the underlying write has committed, matching the same real-time-only-after-it's-real discipline the
 * OpenAPI doc asks for.
 */
@Component
public class RunEventHub {

    private static final Logger log = LoggerFactory.getLogger(RunEventHub.class);
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void publishTimelineEntry(String runId, ScenarioTimelineEntryDto entry) {
        emit(runId, "timeline-entry", entry);
    }

    public void publishRunStatus(String runId, ScenarioRunDto run) {
        emit(runId, "run-status", Map.of(
                "status", run.status(),
                "orderId", run.orderId() == null ? "" : run.orderId(),
                "completedAt", run.completedAt() == null ? "" : run.completedAt().toString()));
    }

    public void close(String runId) {
        List<SseEmitter> emitters = emittersByRun.remove(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // Emitter already gone; nothing more to do.
            }
        }
    }

    private void emit(String runId, String eventName, Object data) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                log.debug("Removing dead SSE emitter for run {}: {}", runId, e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
