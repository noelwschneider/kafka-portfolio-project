package com.orderfulfillment.scenario.runtime;

import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunTimelineEntity;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.dto.ScenarioRunSummaryDto;
import com.orderfulfillment.scenario.dto.ScenarioTimelineEntryDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ScenarioRunMapper {

    private final ObjectMapper objectMapper;

    public ScenarioRunMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ScenarioRunDto toDto(ScenarioRunEntity run, List<ScenarioRunTimelineEntity> timeline) {
        List<ScenarioTimelineEntryDto> entries = timeline.stream().map(this::toDto).toList();
        return new ScenarioRunDto(
                run.getId(), run.getScenarioName(), run.getStatus().name(), run.getCorrelationId(),
                run.getOrderId(), run.getStartedAt(), run.getCompletedAt(), elapsedMs(run), run.getErrorMessage(),
                entries);
    }

    public ScenarioRunSummaryDto toSummaryDto(ScenarioRunEntity run) {
        return new ScenarioRunSummaryDto(
                run.getId(), run.getScenarioName(), run.getStatus().name(), run.getCorrelationId(),
                run.getOrderId(), run.getStartedAt(), run.getCompletedAt(), elapsedMs(run));
    }

    @SuppressWarnings("unchecked")
    private ScenarioTimelineEntryDto toDto(ScenarioRunTimelineEntity entity) {
        Map<String, Object> detail = entity.getDetail() == null
                ? Map.of()
                : objectMapper.readValue(entity.getDetail(), Map.class);
        return new ScenarioTimelineEntryDto(
                entity.getSequence(), entity.getKind().name(), entity.getLabel(), entity.getOccurredAt(), detail);
    }

    private Long elapsedMs(ScenarioRunEntity run) {
        if (run.getCompletedAt() == null) {
            return null;
        }
        return Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis();
    }
}
