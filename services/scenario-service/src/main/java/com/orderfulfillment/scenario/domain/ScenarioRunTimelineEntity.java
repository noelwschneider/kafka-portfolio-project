package com.orderfulfillment.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** docs/db-ownership.md §3 "Scenario Service" — scenario_run_timeline. */
@Entity
@Table(name = "scenario_run_timeline", schema = "scenario_service")
public class ScenarioRunTimelineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimelineKind kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    protected ScenarioRunTimelineEntity() {
    }

    public ScenarioRunTimelineEntity(String runId, int sequence, TimelineKind kind, String label,
                                      Instant occurredAt, String detailJson) {
        this.runId = runId;
        this.sequence = sequence;
        this.kind = kind;
        this.label = label;
        this.occurredAt = occurredAt;
        this.detail = detailJson;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public int getSequence() {
        return sequence;
    }

    public String getLabel() {
        return label;
    }

    public TimelineKind getKind() {
        return kind;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDetail() {
        return detail;
    }
}
