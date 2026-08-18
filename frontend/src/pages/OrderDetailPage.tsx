import { useQuery } from '@tanstack/react-query';
import { getOrder, type OrderDetail } from '../api/orders';
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

export function OrderDetailPage({ orderId, onBack }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => getOrder(orderId),
    // Phase 2: the order now travels through Kafka asynchronously after POST /api/orders returns
    // PENDING, so this poll is what actually surfaces the state transitions to the user — not just
    // a demo-recovery convenience like it was in Phase 1. Polls at 1s while non-terminal, then
    // stops once the order reaches a terminal state (docs/order-state-machine.md §1). SSE
    // (GET /api/orders/stream) replaces this in Phase 5; not implemented yet.
    refetchInterval: (query) => {
      const status = (query.state.data as OrderDetail | undefined)?.status;
      return status && TERMINAL_STATUSES.has(status) ? false : 1000;
    },
  });

  return (
    <section>
      <div className="page-header">
        <h1>Order detail</h1>
        <button onClick={onBack}>Back to orders</button>
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
