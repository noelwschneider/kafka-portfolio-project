import type { ScenarioTimelineEntry } from '../api/scenarios';

// Issue #57 — per-step, color-coded flow attribution for the scenario-run timeline. Attributes each
// timeline entry to the one backend service it genuinely occurred in (or `null` when there isn't a
// real, single-service attribution) and to the topic/endpoint it routed through, so the timeline can
// render a symbol-and-arrow flow layer above the existing detail rows without fabricating anything
// not actually present on the entry.
//
// Scope: the common single/linear-flow case (the 7 of 8 scenarios that produce one order moving
// through order -> inventory/payment -> fulfillment). duplicate-event's non-linear replay is *not*
// specially handled — it renders using the same per-entry rules, which for that scenario means the
// duplicate publish just re-attributes to the same service a second time rather than being flagged as
// a replay. Generalizing to a graph (vs. this linear chain) is explicitly out of scope for this pass.

export type ServiceKey = 'order' | 'payment' | 'inventory' | 'fulfillment';

export const SERVICE_KEYS: ServiceKey[] = ['order', 'payment', 'inventory', 'fulfillment'];

export const SERVICE_LABELS: Record<ServiceKey, string> = {
  order: 'Order Service',
  payment: 'Payment Service',
  inventory: 'Inventory Service',
  fulfillment: 'Fulfillment Service',
};

// Verified against services/scenario-service/src/main/java/com/orderfulfillment/scenario/projection/
// EventProjectionConsumer.java's PRODUCER_BY_TOPIC map: for EVENT entries with detail.phase ===
// 'published', detail.producer is always exactly one of these four strings. That same file's
// javadoc confirms a 'consumed' phase is deliberately never recorded (it would require reading
// another service's processed_events table, which db-ownership.md's one-owner rule forbids) — so
// detail.consumer is not populated by anything in this codebase today and is intentionally not
// consulted here.
function producerToService(producer: string): ServiceKey | null {
  const key = producer.replace(/-service$/, '');
  return (SERVICE_KEYS as string[]).includes(key) ? (key as ServiceKey) : null;
}

// Verified against every recordHttp(...) call site under
// services/scenario-service/src/main/java/com/orderfulfillment/scenario/scenarios/*.java (grepped
// for the literal labels below) — these are the only HTTP labels any scenario actually produces.
// Summary-only labels with no real endpoint behind them (e.g. HighVolumeScenario's "Burst order
// submission complete") intentionally match nothing and fall back to no attribution.
const HTTP_PATH_SERVICE: [RegExp, ServiceKey][] = [
  [/^(?:GET|POST|PUT|DELETE)\s+\/api\/orders/, 'order'],
  [/^(?:GET|POST|PUT|DELETE)\s+\/demo\/payment-behavior/, 'payment'],
  [/^(?:GET|POST|PUT|DELETE)\s+\/demo\/inventory\//, 'inventory'],
  // ConsumerOutageScenario pauses/resumes Inventory Service's own order-created listener through
  // this endpoint (see the scenario's own javadoc) — every current scenario that calls
  // /demo/consumers/... targets Inventory Service specifically, not a generic "some consumer".
  [/^(?:GET|POST|PUT|DELETE)\s+\/demo\/consumers\//, 'inventory'],
];

/** The one service this entry genuinely occurred in, or null when there's no real single-service attribution. */
export function attributeService(entry: ScenarioTimelineEntry): ServiceKey | null {
  if (entry.kind === 'EVENT') {
    const producer = entry.detail?.producer;
    return typeof producer === 'string' ? producerToService(producer) : null;
  }
  if (entry.kind === 'HTTP') {
    for (const [pattern, service] of HTTP_PATH_SERVICE) {
      if (pattern.test(entry.label)) return service;
    }
    return null;
  }
  // STATE_CHANGE entries (e.g. "High-volume batch summary", order state transitions) describe the
  // run or the order as a whole, not one service's action — no attribution, by design.
  return null;
}

/** The topic/endpoint this entry routed through, for the arrow label. */
export function flowRoutingLabel(entry: ScenarioTimelineEntry): string | null {
  if (entry.kind === 'EVENT' && typeof entry.detail?.topic === 'string') return entry.detail.topic;
  if (entry.kind === 'HTTP') return entry.label;
  return null;
}
