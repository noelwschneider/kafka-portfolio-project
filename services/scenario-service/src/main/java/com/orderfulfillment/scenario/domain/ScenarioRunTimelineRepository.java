package com.orderfulfillment.scenario.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRunTimelineRepository extends JpaRepository<ScenarioRunTimelineEntity, Long> {

    List<ScenarioRunTimelineEntity> findByRunIdOrderBySequenceAsc(String runId);

    int countByRunId(String runId);
}
