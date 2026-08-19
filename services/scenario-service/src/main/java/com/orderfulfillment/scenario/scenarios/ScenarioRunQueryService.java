package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.domain.ScenarioRunStatus;
import com.orderfulfillment.scenario.domain.ScenarioRunTimelineRepository;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.dto.ScenarioRunPageDto;
import com.orderfulfillment.scenario.runtime.ScenarioRunMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioRunQueryService {

    private final ScenarioRunRepository runRepository;
    private final ScenarioRunTimelineRepository timelineRepository;
    private final ScenarioRunMapper mapper;

    public ScenarioRunQueryService(ScenarioRunRepository runRepository,
                                    ScenarioRunTimelineRepository timelineRepository, ScenarioRunMapper mapper) {
        this.runRepository = runRepository;
        this.timelineRepository = timelineRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ScenarioRunDto getRun(String runId) {
        ScenarioRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("SCENARIO_RUN_NOT_FOUND", "No scenario run with id " + runId));
        return mapper.toDto(run, timelineRepository.findByRunIdOrderBySequenceAsc(runId));
    }

    @Transactional(readOnly = true)
    public ScenarioRunPageDto listRuns(String scenarioName, String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<ScenarioRunEntity> result;
        if (scenarioName != null && status != null) {
            result = runRepository.findByScenarioNameAndStatusOrderByStartedAtDesc(
                    scenarioName, ScenarioRunStatus.valueOf(status), pageRequest);
        } else if (scenarioName != null) {
            result = runRepository.findByScenarioNameOrderByStartedAtDesc(scenarioName, pageRequest);
        } else if (status != null) {
            result = runRepository.findByStatusOrderByStartedAtDesc(ScenarioRunStatus.valueOf(status), pageRequest);
        } else {
            result = runRepository.findAllByOrderByStartedAtDesc(pageRequest);
        }
        return new ScenarioRunPageDto(
                result.getContent().stream().map(mapper::toSummaryDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
