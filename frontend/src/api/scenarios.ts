import { apiFetch, SCENARIO_SERVICE_BASE_URL } from './client';

// Mirrors docs/openapi/scenario-service.yaml — the Scenario Service's frozen contract. Schema
// names below match the OpenAPI component names exactly so the mapping is obvious.

export type ScenarioName =
  | 'standard-order'
  | 'out-of-stock'
  | 'payment-failure'
  | 'duplicate-event'
  | 'consumer-outage'
  | 'poison-message'
  | 'inventory-contention'
  | 'high-volume';

export type ScenarioRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ScenarioDefinition {
  name: ScenarioName;
  title: string;
  description: string;
  demonstrates: string[];
  expectedTerminalStatus: string | null;
  available: boolean;
}

export type TimelineEntryKind = 'HTTP' | 'EVENT' | 'STATE_CHANGE';

// Deliberately an open, all-optional bag — docs/openapi/scenario-service.yaml's
// ScenarioTimelineEntry.detail is "an open object, and every field inside it is optional" so that
// an unavailable field is absent rather than fabricated as zero/blank. Known keys are typed for
// convenience; unknown keys still round-trip through the index signature.
export interface ScenarioTimelineEntryDetail {
  phase?: 'published' | 'consumed' | string;
  topic?: string;
  partition?: number;
  offset?: number;
  eventId?: string;
  correlationId?: string;
  aggregateId?: string;
  producer?: string;
  consumer?: string;
  durationMs?: number;
  retryCount?: number;
  statusCode?: number;
  orderId?: string;
  status?: string;
  error?: string;
  [key: string]: unknown;
}

export interface ScenarioTimelineEntry {
  sequence: number;
  kind: TimelineEntryKind;
  label: string;
  occurredAt: string;
  detail: ScenarioTimelineEntryDetail | null;
}

export interface ScenarioRun {
  id: string;
  scenarioName: ScenarioName;
  status: ScenarioRunStatus;
  correlationId: string;
  orderId: string | null;
  startedAt: string;
  completedAt: string | null;
  elapsedMs: number | null;
  errorMessage: string | null;
  timeline: ScenarioTimelineEntry[];
}

export interface ScenarioRunSummary {
  id: string;
  scenarioName: ScenarioName;
  status: ScenarioRunStatus;
  correlationId: string;
  orderId: string | null;
  startedAt: string;
  completedAt: string | null;
  elapsedMs: number | null;
}

export interface ScenarioRunPage {
  content: ScenarioRunSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ResetResult {
  inventoryRestored: boolean;
  consumersResumed: string[];
  paymentBehaviorCleared: boolean;
  resetAt: string;
}

export function listScenarios(): Promise<ScenarioDefinition[]> {
  return apiFetch<ScenarioDefinition[]>(SCENARIO_SERVICE_BASE_URL, '/demo/scenarios');
}

export function runScenario(scenarioName: ScenarioName): Promise<ScenarioRun> {
  return apiFetch<ScenarioRun>(SCENARIO_SERVICE_BASE_URL, `/demo/scenarios/${scenarioName}`, {
    method: 'POST',
  });
}

export interface ListScenarioRunsParams {
  scenarioName?: ScenarioName;
  status?: ScenarioRunStatus;
  page?: number;
  size?: number;
}

export function listScenarioRuns(params: ListScenarioRunsParams = {}): Promise<ScenarioRunPage> {
  const query = new URLSearchParams();
  if (params.scenarioName) query.set('scenarioName', params.scenarioName);
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return apiFetch<ScenarioRunPage>(SCENARIO_SERVICE_BASE_URL, `/demo/scenario-runs?${query.toString()}`);
}

export function getScenarioRun(runId: string): Promise<ScenarioRun> {
  return apiFetch<ScenarioRun>(SCENARIO_SERVICE_BASE_URL, `/demo/scenario-runs/${runId}`);
}

export function scenarioRunStreamUrl(runId: string): string {
  return `${SCENARIO_SERVICE_BASE_URL}/demo/scenario-runs/${runId}/stream`;
}

export function resetDemoEnvironment(): Promise<ResetResult> {
  return apiFetch<ResetResult>(SCENARIO_SERVICE_BASE_URL, '/demo/reset', { method: 'POST' });
}
