import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getOrder, type OrderDetail } from '../api/orders';
import { ORDER_SERVICE_BASE_URL, subscribeToStream } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';

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

      {isLoading && <p>Loading order…</p>}
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
              <dd>{new Date(data.createdAt).toLocaleString()}</dd>
              <dt>Updated</dt>
              <dd>{new Date(data.updatedAt).toLocaleString()}</dd>
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
                <span>{new Date(entry.occurredAt).toLocaleString()}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
