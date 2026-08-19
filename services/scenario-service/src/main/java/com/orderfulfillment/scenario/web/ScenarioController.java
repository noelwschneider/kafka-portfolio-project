package com.orderfulfillment.scenario.web;

import com.orderfulfillment.scenario.catalog.ScenarioCatalog;
import com.orderfulfillment.scenario.catalog.ScenarioDefinitionSpec;
import com.orderfulfillment.scenario.dto.ScenarioDefinitionDto;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.scenarios.ScenarioExecutionService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/scenario-service.yaml — /demo/scenarios. */
@RestController
public class ScenarioController {

    private final ScenarioCatalog catalog;
    private final ScenarioExecutionService executionService;

    public ScenarioController(ScenarioCatalog catalog, ScenarioExecutionService executionService) {
        this.catalog = catalog;
        this.executionService = executionService;
    }

    @GetMapping("/demo/scenarios")
    public java.util.List<ScenarioDefinitionDto> listScenarios() {
        return catalog.all().stream().map(this::toDto).toList();
    }

    @PostMapping("/demo/scenarios/{scenarioName}")
    public ResponseEntity<ScenarioRunDto> runScenario(@PathVariable String scenarioName) {
        ScenarioRunDto run = executionService.start(scenarioName);
        return ResponseEntity.accepted()
                .location(URI.create("/demo/scenario-runs/" + run.id()))
                .body(run);
    }

    private ScenarioDefinitionDto toDto(ScenarioDefinitionSpec spec) {
        return new ScenarioDefinitionDto(spec.name(), spec.title(), spec.description(), spec.demonstrates(),
                spec.expectedTerminalStatus(), spec.available());
    }
}
