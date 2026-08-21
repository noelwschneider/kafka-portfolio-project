package com.orderfulfillment.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for Sprint 2 goal 4's idle auto-reset (W4 of
 * docs/agent-reports/sprint-2/deployment-code-changes-briefing.md). Off by default so it never
 * fires during local development — see {@code application-production.yml}, the only profile that
 * flips {@code enabled} to true.
 *
 * @param enabled whether {@link com.orderfulfillment.scenario.admin.IdleResetScheduler} is active.
 * @param idlePeriodMs how long the system must show no scenario activity before an idle reset
 *     fires. 15 minutes is the sprint-2 decision (see the briefing's "Open questions" section, now
 *     closed).
 * @param checkIntervalMs how often the scheduler checks whether the idle period has elapsed. Cheap
 *     (one in-memory check plus, only when that passes, one indexed query), so this can run far more
 *     often than the idle period itself without meaningfully affecting reset latency accuracy.
 */
@ConfigurationProperties(prefix = "orderfulfillment.idle-reset")
public record IdleResetProperties(boolean enabled, long idlePeriodMs, long checkIntervalMs) {
}
