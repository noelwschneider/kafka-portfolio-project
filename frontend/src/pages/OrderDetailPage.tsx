import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getOrder, type OrderDetail } from '../api/orders';
import { ORDER_SERVICE_BASE_URL, subscribeToStream } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { LoadingHint } from '../components/LoadingHint';
import { queryEvents, type EventQueryFilters, type EventRecord } from '../api/events';

interface Props {
  orderId: string;
  onBack: () => void;
}

// docs/order-state-machine.md §1 — terminal states. Kept in sync manually since the frontend has
// no generated client from the frozen OpenAPI/state-machine docs this phase.
const TERMINAL_STATUSES = new Set([
  'REJECTED_OUT_OF_STOCK',
  'PAYMENT_FAILED',
  'FULFILLED',
  'FAILED',
]);

// docs/openapi/order-service.yaml's GET /api/orders/stream: one `order-status-changed` SSE event
// per lifecycle transition. Message schema isn't frozen (Phase 0 only froze the endpoint/content
// type/event name), so this is read defensively and only used to know *that* something changed —
// the actual order is always re-fetched from GET /api/orders/{id}, never reconstructed from the
// SSE payload alone.
interface OrderStatusChangedMessage {
  orderId?: string;
  status?: string;
  previousStatus?: string;
  eventId?: string | null;
  correlationId?: string;
  occurredAt?: string;
}

type StreamState = 'connecting' | 'live' | 'unavailable';

// Same compact "Aug 26, 2:14 PM" treatment issue #20 applied to the Orders table's Created column
// (frontend/src/pages/OrdersListPage.tsx) — no year, no seconds.
const timestampFormatter = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

// Order-scoped replacement for the retired standalone Event Explorer page (issue #7). Same
// GET /demo/events projection and the same EventRecord shape — `orderId` is now fixed to this
// page's order instead of being one filter among several, so the remaining filters (type,
// correlation id, service, topic, dead-lettered) are tucked behind a details/summary disclosure
// rather than always-visible form chrome.
function EventTimelineEntry({ event }: { event: EventRecord }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className="timeline-entry">
      <div className="timeline-row" onClick={() => setExpanded((e) => !e)}>
        <span className="timeline-time">
          {new Date(event.occurredAt).toLocaleTimeString(undefined, {
            hour12: false,
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
          })}
        </span>
        <span className="timeline-kind">{event.eventType}</span>
        <span className="timeline-label">
          {event.topic} {event.deadLettered && <span className="badge badge-muted">DLQ</span>}
        </span>
        <span className="timeline-expand">{expanded ? '▾' : '▸'}</span>
      </div>
      {expanded && (
        <dl className="timeline-detail">
          <div className="timeline-detail-row">
            <dt>eventId</dt>
            <dd>{event.eventId}</dd>
          </div>
          <div className="timeline-detail-row">
            <dt>correlationId</dt>
            <dd>{event.correlationId}</dd>
          </div>
          <div className="timeline-detail-row">
            <dt>producer</dt>
            <dd>{event.producer}</dd>
          </div>
          <div className="timeline-detail-row">
            <dt>partition / offset</dt>
            <dd>{event.partition} / {event.offset}</dd>
          </div>
          <div className="timeline-detail-row">
            <dt>payload</dt>
            <dd>{JSON.stringify(event.payload)}</dd>
          </div>
        </dl>
      )}
    </li>
  );
}

function EventTimelineSection({ orderId }: { orderId: string }) {
  const [filters, setFilters] = useState<Omit<EventQueryFilters, 'orderId'>>({});

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['order-events', orderId, filters],
    queryFn: () => queryEvents({ ...filters, orderId }),
  });

  return (
    <>
      <h3>Events</h3>
      <details className="scenario-card-details">
        <summary>Filter</summary>
        <div className="event-filters">
          <label>
            Event type
            <input
              value={filters.eventType ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, eventType: e.target.value || undefined }))}
              placeholder="OrderCreated"
            />
          </label>
          <label>
            Correlation ID
            <input
              value={filters.correlationId ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, correlationId: e.target.value || undefined }))}
            />
          </label>
          <label>
            Service
            <input
              value={filters.service ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, service: e.target.value || undefined }))}
              placeholder="inventory-service"
            />
          </label>
          <label>
            Topic
            <input
              value={filters.topic ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, topic: e.target.value || undefined }))}
              placeholder="orders.events"
            />
          </label>
          <label>
            Dead-lettered
            <select
              value={filters.deadLettered === undefined ? '' : String(filters.deadLettered)}
              onChange={(e) =>
                setFilters((f) => ({
                  ...f,
                  deadLettered: e.target.value === '' ? undefined : e.target.value === 'true',
                }))
              }
            >
              <option value="">Any</option>
              <option value="true">Dead-lettered only</option>
              <option value="false">Not dead-lettered</option>
            </select>
          </label>
        </div>
      </details>

      {isLoading && <LoadingHint label="Loading events…" />}
      {isError && (
        <p className="error">Could not reach Scenario Service: {(error as Error).message}.</p>
      )}
      {data && data.length === 0 && <p className="hint">No events match these filters.</p>}
      {data && data.length > 0 && (
        <ol className="timeline">
          {data.map((event) => (
            <EventTimelineEntry key={event.eventId} event={event} />
          ))}
        </ol>
      )}
    </>
  );
}

export function OrderDetailPage({ orderId, onBack }: Props) {
  const queryClient = useQueryClient();
  const [streamState, setStreamState] = useState<StreamState>('connecting');
  const [lastLiveEvent, setLastLiveEvent] = useState<OrderStatusChangedMessage | null>(null);
  const fellBackRef = useRef(false);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => getOrder(orderId),
    // Primary live-update mechanism is now the SSE stream below (Phase 5's "add SSE/live updates"
    // goal). This poll is kept only as a fallback for when the stream never opens or drops
    // permanently, so the page still self-heals against a backend that doesn't support SSE yet.
    refetchInterval: (query) => {
      const status = (query.state.data as OrderDetail | undefined)?.status;
      if (status && TERMINAL_STATUSES.has(status)) return false;
      return streamState === 'live' ? false : 1000;
    },
  });

  useEffect(() => {
    setStreamState('connecting');
    fellBackRef.current = false;

    const url = `${ORDER_SERVICE_BASE_URL}/api/orders/stream?orderId=${encodeURIComponent(orderId)}`;
    const unsubscribe = subscribeToStream(
      url,
      {
        onOpen: () => setStreamState('live'),
        onMessage: (_name, raw) => {
          setStreamState('live');
          try {
            const message = JSON.parse(raw) as OrderStatusChangedMessage;
            setLastLiveEvent(message);
          } catch {
            // Malformed payload — still a signal the connection is live; refetch anyway.
          }
          queryClient.invalidateQueries({ queryKey: ['order', orderId] });
          queryClient.invalidateQueries({ queryKey: ['order-events', orderId] });
        },
        onError: () => {
          // EventSource retries on its own; if it never recovers the poll above keeps the page
          // correct, just less instantly. We don't tear the connection down here.
          if (!fellBackRef.current) {
            fellBackRef.current = true;
            setStreamState('unavailable');
          }
        },
      },
      ['order-status-changed'],
    );

    return unsubscribe;
  }, [orderId, queryClient]);

  return (
    <section>
      <div className="page-header">
        <h1>Order detail</h1>
        <button onClick={onBack}>Back to orders</button>
      </div>

      <div className={`stream-indicator stream-${streamState}`}>
        {streamState === 'live' && 'Live — updates via SSE'}
        {streamState === 'connecting' && 'Connecting to live updates…'}
        {streamState === 'unavailable' && 'Live stream unavailable — falling back to polling'}
      </div>

      {isLoading && <LoadingHint label="Loading order…" />}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && (
        <div className="order-detail">
          <div className="order-summary-card">
            <h2>{data.id}</h2>
            <StatusBadge status={data.status} />
            <dl>
              <dt>Customer</dt>
              <dd>{data.customerId}</dd>
              <dt>Total</dt>
              <dd>${data.totalAmount.toFixed(2)}</dd>
              <dt>Created</dt>
              <dd>{timestampFormatter.format(new Date(data.createdAt))}</dd>
              <dt>Updated</dt>
              <dd>{timestampFormatter.format(new Date(data.updatedAt))}</dd>
            </dl>
          </div>

          {lastLiveEvent && (
            <p className="stream-last-event">
              Last live update: {lastLiveEvent.previousStatus ?? '?'} → {lastLiveEvent.status ?? '?'}
              {lastLiveEvent.occurredAt ? ` at ${new Date(lastLiveEvent.occurredAt).toLocaleTimeString()}` : ''}
            </p>
          )}

          <h3>Items</h3>
          <table>
            <thead>
              <tr>
                <th>SKU</th>
                <th>Quantity</th>
                <th>Unit price</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.sku}>
                  <td>{item.sku}</td>
                  <td>{item.quantity}</td>
                  <td>${item.unitPrice.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3>Status history</h3>
          <ol className="status-history">
            {data.statusHistory.map((entry, index) => (
              <li key={index}>
                <StatusBadge status={entry.status} />
                <span>{timestampFormatter.format(new Date(entry.occurredAt))}</span>
              </li>
            ))}
          </ol>

          <EventTimelineSection orderId={orderId} />
        </div>
      )}
    </section>
  );
}
