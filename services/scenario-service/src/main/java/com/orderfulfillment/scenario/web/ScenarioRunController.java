package com.orderfulfillment.scenario.web;

import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.dto.ScenarioRunPageDto;
import com.orderfulfillment.scenario.runtime.RunEventHub;
import com.orderfulfillment.scenario.scenarios.ScenarioRunQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** docs/openapi/scenario-service.yaml — /demo/scenario-runs. */
@RestController
public class ScenarioRunController {

    private final ScenarioRunQueryService queryService;
    private final RunEventHub runEventHub;

    public ScenarioRunController(ScenarioRunQueryService queryService, RunEventHub runEventHub) {
        this.queryService = queryService;
        this.runEventHub = runEventHub;
    }

    @GetMapping("/demo/scenario-runs")
    public ScenarioRunPageDto listRuns(@RequestParam(required = false) String scenarioName,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return queryService.listRuns(scenarioName, status, page, size);
    }

    @GetMapping("/demo/scenario-runs/{runId}")
    public ScenarioRunDto getRun(@PathVariable String runId) {
        return queryService.getRun(runId);
    }

    @GetMapping("/demo/scenario-runs/{runId}/stream")
    public SseEmitter streamRun(@PathVariable String runId) {
        // 404s if unknown, so a client cannot subscribe to a run that will never exist.
        queryService.getRun(runId);
        return runEventHub.subscribe(runId);
    }
}
