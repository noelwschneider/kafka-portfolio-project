import type { ScenarioTimelineEntry, ScenarioTimelineEntryDetail } from '../api/scenarios';

// Turns a raw timeline entry (kind + label + detail) into a short, plain-language headline that
// explains what happened and why it matters, instead of surfacing the raw HTTP path / Kafka event
// type name as the primary text. Mirrors docs/planning/sprint-1/frontend-design.md §12.4: never
// fabricate a data value — every headline below is either static prose about the *mechanism*
// (sourced from docs/events/event-catalog.md §3 and docs/order-state-machine.md) or interpolates a
// value that is actually present on the entry itself (detail.statusCode, detail.orderId, the
// dynamic listenerId embedded in the label, etc.).
//
// The vocabulary of labels this maps is the complete set the scenario-service backend actually
// produces (see docs/events/event-catalog.md and the scenario-service source) — HTTP requests the
// scenario runner issues, the 8 catalog event types, and the order-state-machine statuses. Anything
// outside that vocabulary still gets a readable fallback rather than blowing up.
//
// Each entry also carries a short `title` (2-4 words, noun-phrase, title case) alongside the
// full-sentence `headline`, so a viewer can scan down the timeline reading just the titles first,
// then read the fuller sentence for detail on whichever step they care about.

export interface Narration {
  title: string;
  headline: string;
}

function narrateHttp(label: string, detail: ScenarioTimelineEntryDetail | null): Narration {
  if (label === 'POST /api/orders') {
    const status = detail?.statusCode;
    if (status != null) {
      return { title: 'Order Submitted', headline: `Scenario submitted a new order to Order Service (HTTP ${status})` };
    }
    return { title: 'Order Submitted', headline: 'Scenario submitted a new order to Order Service' };
  }
  if (label === 'PUT /demo/payment-behavior') {
    return { title: 'Payment Behavior Configured', headline: 'Scenario configured Payment Service to simulate a specific outcome on the next authorization' };
  }
  if (label === 'DELETE /demo/payment-behavior') {
    return { title: 'Payment Behavior Cleared', headline: 'Scenario cleared the simulated payment behavior, restoring normal authorization' };
  }
  if (label.startsWith('POST /demo/consumers/') && label.endsWith('/pause')) {
    const listenerId = label.slice('POST /demo/consumers/'.length, -'/pause'.length);
    return { title: 'Consumer Paused', headline: `Scenario paused the \`${listenerId}\` Kafka consumer to simulate an outage` };
  }
  if (label.startsWith('POST /demo/consumers/') && label.endsWith('/resume')) {
    const listenerId = label.slice('POST /demo/consumers/'.length, -'/resume'.length);
    return { title: 'Consumer Resumed', headline: `Scenario resumed the \`${listenerId}\` Kafka consumer, ending the simulated outage` };
  }
  if (label === 'Burst order submission complete') {
    return { title: 'Burst Submission Complete', headline: 'Scenario finished submitting a burst of concurrent orders' };
  }
  return { title: 'HTTP Call', headline: `System recorded an HTTP call: ${label}` };
}

// docs/events/event-catalog.md §3 — one sentence of mechanism per event type, safe to paraphrase
// because it describes what the event means generically, not per-run fabricated data.
const EVENT_MEANING: Record<string, string> = {
  OrderCreated:
    'Order Service persisted the order as PENDING and published `OrderCreated` to Kafka for Inventory Service to react to',
  InventoryReserved:
    'Inventory Service reserved every line against stock in one transaction and published `InventoryReserved` so Order Service can advance the order',
  InventoryReservationFailed:
    'Inventory Service could not satisfy at least one line — a business rejection, not an error — and published `InventoryReservationFailed`',
  InventoryReleased:
    'Inventory Service compensated by releasing the earlier reservation after a payment rejection, publishing `InventoryReleased`',
  PaymentRequested:
    'Order Service asked Payment Service to authorize the charge, carrying an idempotency key, via `PaymentRequested`',
  PaymentAuthorized:
    'Payment Service authorized the charge and published `PaymentAuthorized` — this project\'s one fan-out event, consumed independently by both Order Service and Fulfillment Service',
  PaymentRejected:
    'The payment simulator declined the charge — a non-retryable business outcome — triggering Inventory\'s compensating release via `PaymentRejected`',
  ShipmentCreated:
    'Fulfillment Service created a shipment after consuming `PaymentAuthorized`, publishing `ShipmentCreated` as the order reaches FULFILLED',
};

// Short title per event type, independent of published/consumed phase — the phase is still called
// out in the full headline sentence, but the scan-friendly title just names the domain fact.
const EVENT_TITLE: Record<string, string> = {
  OrderCreated: 'Order Created',
  InventoryReserved: 'Inventory Reserved',
  InventoryReservationFailed: 'Inventory Reservation Failed',
  InventoryReleased: 'Inventory Released',
  PaymentRequested: 'Payment Requested',
  PaymentAuthorized: 'Payment Authorized',
  PaymentRejected: 'Payment Rejected',
  ShipmentCreated: 'Shipment Created',
};

function narrateEvent(label: string, detail: ScenarioTimelineEntryDetail | null): Narration {
  const meaning = EVENT_MEANING[label];
  const title = EVENT_TITLE[label] ?? 'Kafka Event';
  const phase = detail?.phase;
  if (meaning) {
    if (phase === 'published') return { title, headline: meaning };
    if (phase === 'consumed') {
      const consumer = detail?.consumer;
      return {
        title,
        headline: consumer
          ? `${consumer} consumed \`${label}\` from Kafka — ${meaning}`
          : `A service consumed \`${label}\` from Kafka — ${meaning}`,
      };
    }
    return { title, headline: meaning };
  }
  return { title, headline: `System recorded Kafka event \`${label}\`${phase ? ` (${phase})` : ''}` };
}

// docs/order-state-machine.md — one sentence per terminal/intermediate status, describing what the
// order reaching that status means in the fulfillment flow.
const STATE_MEANING: Record<string, string> = {
  PENDING: 'The order was created and is awaiting inventory reservation',
  INVENTORY_RESERVED: 'Inventory was reserved for every line, and the order is ready for payment',
  REJECTED_OUT_OF_STOCK: 'The order was rejected because inventory could not cover every line',
  PAYMENT_PENDING: 'The order is awaiting payment authorization',
  PAID: 'Payment was authorized for the order',
  PAYMENT_FAILED: 'Payment was declined for the order, and inventory is being released',
  FULFILLMENT_PENDING: 'The order is awaiting shipment creation',
  FULFILLED: 'A shipment was created and the order has reached its final, successful state',
  FAILED: 'The order reached a terminal failure state',
};

// Short title per order status, mirroring STATE_MEANING's keys.
const STATE_TITLE: Record<string, string> = {
  PENDING: 'Order Pending',
  INVENTORY_RESERVED: 'Inventory Reserved',
  REJECTED_OUT_OF_STOCK: 'Order Rejected',
  PAYMENT_PENDING: 'Payment Pending',
  PAID: 'Order Paid',
  PAYMENT_FAILED: 'Payment Failed',
  FULFILLMENT_PENDING: 'Fulfillment Pending',
  FULFILLED: 'Order Fulfilled',
  FAILED: 'Order Failed',
};

function narrateStateChange(label: string, detail: ScenarioTimelineEntryDetail | null): Narration {
  if (label === 'High-volume batch summary') {
    return { title: 'Batch Summary', headline: 'Scenario recorded an aggregate summary across the whole batch of orders, not a single order\'s state' };
  }
  const prefix = 'Order ';
  if (label.startsWith(prefix)) {
    const status = label.slice(prefix.length);
    const meaning = STATE_MEANING[status];
    if (meaning) {
      const orderId = detail?.orderId;
      const title = STATE_TITLE[status] ?? 'State Changed';
      return { title, headline: orderId ? `Order ${orderId} reached ${status}: ${meaning}` : `Order reached ${status}: ${meaning}` };
    }
  }
  return { title: 'State Changed', headline: `System recorded a state change: ${label}` };
}

/**
 * Maps a raw scenario timeline entry to a short narrative headline for display as the primary
 * text of the (collapsed) timeline row. Falls back to a readable, generic sentence for any
 * kind/label combination not explicitly covered above.
 */
export function narrateTimelineEntry(entry: ScenarioTimelineEntry): Narration {
  switch (entry.kind) {
    case 'HTTP':
      return narrateHttp(entry.label, entry.detail);
    case 'EVENT':
      return narrateEvent(entry.label, entry.detail);
    case 'STATE_CHANGE':
      return narrateStateChange(entry.label, entry.detail);
    default:
      return { title: 'System Event', headline: `System recorded: ${entry.label}` };
  }
}
