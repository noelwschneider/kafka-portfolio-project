package com.orderfulfillment.scenario.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRunRepository extends JpaRepository<ScenarioRunEntity, String> {

    Page<ScenarioRunEntity> findByScenarioNameAndStatusOrderByStartedAtDesc(
            String scenarioName, ScenarioRunStatus status, Pageable pageable);

    Page<ScenarioRunEntity> findByScenarioNameOrderByStartedAtDesc(String scenarioName, Pageable pageable);

    Page<ScenarioRunEntity> findByStatusOrderByStartedAtDesc(ScenarioRunStatus status, Pageable pageable);

    Page<ScenarioRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<ScenarioRunEntity> findByScenarioNameAndStatus(String scenarioName, ScenarioRunStatus status);

    boolean existsByStatus(ScenarioRunStatus status);
}
