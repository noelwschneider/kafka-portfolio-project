// Event Explorer data client.
//
// Mirrors docs/openapi/scenario-service.yaml's GET /demo/events — the event-projection query
// endpoint Scenario Service added in Phase 5 (docs/CHANGELOG-contracts.md) to resolve
// docs/db-ownership.md's "Event Explorer's backing store has no owner yet". Every field on
// EventRecord is genuinely observable by a direct Kafka consumer of the record; there is
// deliberately no "consumed" phase, `durationMs`, or `retryCount` — those live inside each
// service's own processed_events row, which this projection may not read cross-schema.
import { apiFetch, SCENARIO_SERVICE_BASE_URL } from './client';

export interface EventRecord {
  eventId: string;
  eventType: string;
  eventVersion: number;
  occurredAt: string;
  correlationId: string;
  aggregateId: string;
  topic: string;
  partition: number;
  offset: number;
  producer: string;
  deadLettered: boolean;
  payload: Record<string, unknown>;
}

export interface EventRecordPage {
  content: EventRecord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EventQueryFilters {
  eventType?: string;
  orderId?: string;
  correlationId?: string;
  service?: string;
  topic?: string;
  deadLettered?: boolean;
}

export type EventQueryResult =
  | { wired: true; events: EventRecord[] }
  | { wired: false; reason: string; events: EventRecord[] };

export async function queryEvents(filters: EventQueryFilters): Promise<EventQueryResult> {
  const query = new URLSearchParams();
  if (filters.eventType) query.set('eventType', filters.eventType);
  // The API's aggregateId is always an orderId in this system (docs/events/event-catalog.md §1),
  // which is what the UI's "Order ID" filter means.
  if (filters.orderId) query.set('aggregateId', filters.orderId);
  if (filters.correlationId) query.set('correlationId', filters.correlationId);
  // The API calls this "producer" (the publishing/owning service); the UI calls it "Service".
  if (filters.service) query.set('producer', filters.service);
  if (filters.topic) query.set('topic', filters.topic);
  if (filters.deadLettered !== undefined) query.set('deadLettered', String(filters.deadLettered));
  query.set('size', '50');

  const page = await apiFetch<EventRecordPage>(
    SCENARIO_SERVICE_BASE_URL,
    `/demo/events?${query.toString()}`,
  );
  return { wired: true, events: page.content };
}
