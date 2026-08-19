package com.orderfulfillment.scenario.web;

import com.orderfulfillment.scenario.dto.EventRecordPageDto;
import com.orderfulfillment.scenario.projection.EventQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 5 addition: {@code GET /demo/events}, the Event Explorer query endpoint that resolves
 * docs/db-ownership.md §4's "Event Explorer's backing store has no owner yet" — see
 * docs/CHANGELOG-contracts.md and docs/agent-reports/phase-5-scenario-service.md. Added under
 * {@code /demo} like every other Scenario Service path in docs/openapi/scenario-service.yaml.
 */
@RestController
public class EventController {

    private final EventQueryService queryService;

    public EventController(EventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/demo/events")
    public EventRecordPageDto listEvents(@RequestParam(required = false) String eventType,
                                          @RequestParam(required = false) String aggregateId,
                                          @RequestParam(required = false) UUID correlationId,
                                          @RequestParam(required = false) String producer,
                                          @RequestParam(required = false) String topic,
                                          @RequestParam(required = false) Boolean deadLettered,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return queryService.search(eventType, aggregateId, correlationId, producer, topic, deadLettered, page, size);
    }
}
