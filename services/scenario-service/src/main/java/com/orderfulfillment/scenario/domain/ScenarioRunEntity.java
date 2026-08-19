package com.orderfulfillment.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** docs/db-ownership.md §3 "Scenario Service" — scenario_runs. */
@Entity
@Table(name = "scenario_runs", schema = "scenario_service")
public class ScenarioRunEntity {

    @Id
    private String id;

    @Column(name = "scenario_name", nullable = false)
    private String scenarioName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioRunStatus status;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    protected ScenarioRunEntity() {
    }

    public ScenarioRunEntity(String id, String scenarioName, UUID correlationId, Instant startedAt) {
        this.id = id;
        this.scenarioName = scenarioName;
        this.status = ScenarioRunStatus.RUNNING;
        this.correlationId = correlationId;
        this.startedAt = startedAt;
    }

    public String getId() {
        return id;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public ScenarioRunStatus getStatus() {
        return status;
    }

    public void setStatus(ScenarioRunStatus status) {
        this.status = status;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
